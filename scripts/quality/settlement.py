#!/usr/bin/env python3
"""Capture non-secret local state and reconcile one quality run's orders and stock.

The snapshot contains selected columns only. A Redis idempotency result is a lead
to an order ID; neither an absent/expired key nor a result proves DB acceptance.
"""

import argparse
import hashlib
import json
import os
import subprocess
import uuid
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path


TABLES = {
    "paymentEvents": ("payment", "payment_event", ["id", "order_id", "status", "created_at", "updated_at", "last_status_changed_at", "executed_at", "approved_at"],
                      ["id", "orderId", "status", "createdAt", "updatedAt", "lastStatusChangedAt", "executedAt", "approvedAt"]),
    "paymentOrders": ("payment", "payment_order", ["id", "payment_event_id", "order_id", "product_id", "quantity", "amount", "status"],
                      ["id", "paymentEventId", "orderId", "productId", "quantity", "amount", "status"]),
    "holdRecords": ("payment", "stock_hold_record", ["id", "order_id", "product_id", "quantity", "status", "created_at", "updated_at"],
                    ["id", "orderId", "productId", "quantity", "status", "createdAt", "updatedAt"]),
    "paymentHistories": ("payment", "payment_history", ["id", "payment_event_id", "order_id", "previous_status", "current_status", "change_status_at"],
                         ["id", "paymentEventId", "orderId", "previousStatus", "currentStatus", "changeStatusAt"]),
    "paymentOutbox": ("payment", "payment_outbox", ["id", "order_id", "status", "retry_count", "next_retry_at", "in_flight_at"],
                      ["id", "orderId", "status", "retryCount", "nextRetryAt", "inFlightAt"]),
    "pgInbox": ("pg", "pg_inbox", ["id", "order_id", "status", "amount", "attempt", "created_at", "updated_at"],
                ["id", "orderId", "status", "amount", "attempt", "createdAt", "updatedAt"]),
    "pgOutbox": ("pg", "pg_outbox", ["id", "topic", "available_at", "processed_at", "created_at"],
                 ["id", "topic", "availableAt", "processedAt", "createdAt"]),
    "stockCommitDedupe": ("product", "stock_commit_dedupe", ["event_uuid", "order_id", "product_id", "qty", "created_at"],
                          ["eventUuid", "orderId", "productId", "quantity", "createdAt"]),
}

DATABASES = {
    "payment": ("MYSQL_PAYMENT_CONTAINER", "payment-mysql-payment", "MYSQL_PAYMENT_DB", "payment-platform", "MYSQL_PAYMENT_USER", "MYSQL_PAYMENT_PASSWORD"),
    "pg": ("MYSQL_PG_CONTAINER", "payment-mysql-pg", "MYSQL_PG_DB", "pg", "MYSQL_PG_USER", "MYSQL_PG_PASSWORD"),
    "product": ("MYSQL_PRODUCT_CONTAINER", "payment-mysql-product", "MYSQL_PRODUCT_DB", "product", "MYSQL_PRODUCT_USER", "MYSQL_PRODUCT_PASSWORD"),
}

INT_FIELDS = {"id", "paymentEventId", "productId", "quantity", "retryCount", "attempt"}
TERMINAL_EVENT_STATUSES = {"DONE", "FAILED", "EXPIRED"}
ACTIVE_OUTBOX_STATUSES = {"PENDING", "IN_FLIGHT"}
UNSETTLED_INBOX_STATUSES = {"NONE", "PENDING", "IN_PROGRESS", "QUARANTINED"}


def _stock_commit_uuid(order_id, product_id):
    seed = f"stock-commit:{order_id}:{product_id}".encode("utf-8")
    return str(uuid.UUID(bytes=hashlib.md5(seed).digest(), version=3))


def _global_residue(snapshot, phase, pending, failures):
    """The dedicated local test starts and ends only after old work drains."""
    for event in snapshot["paymentEvents"]:
        if event["status"] not in TERMINAL_EVENT_STATUSES:
            target = failures if phase == "after" and event["status"] == "QUARANTINED" else pending
            target.append(f"{phase} nonterminal payment_event: {event.get('orderId')}/{event['status']}")
    for hold in snapshot["holdRecords"]:
        if hold["status"] == "NOISE":
            pending.append(f"{phase} NOISE hold: {hold.get('orderId')}/{hold.get('productId')}")
    for row in snapshot["paymentOutbox"]:
        if row["status"] in ACTIVE_OUTBOX_STATUSES:
            pending.append(f"{phase} active payment_outbox: {row.get('orderId')}/{row['status']}")
    for row in snapshot["pgInbox"]:
        if row["status"] in UNSETTLED_INBOX_STATUSES:
            pending.append(f"{phase} unsettled pg_inbox: {row.get('orderId')}/{row['status']}")
    for row in snapshot["pgOutbox"]:
        if row.get("processedAt") is None:
            pending.append(f"{phase} unprocessed pg_outbox: {row.get('id')}")


def _run(command, *, input_text=None):
    completed = subprocess.run(command, input=input_text, text=True, capture_output=True, timeout=120, check=False)
    if completed.returncode:
        # mysql stderr can contain a supplied SQL fragment; our SQL is fixed and contains no secrets.
        raise RuntimeError(f"command failed ({completed.returncode}): {command[0]} {command[1:3]}: {completed.stderr.strip()}")
    return completed.stdout


def _mysql(role, sql):
    container_var, container_default, db_var, db_default, user_var, password_var = DATABASES[role]
    container = os.environ.get(container_var, container_default)
    database = os.environ.get(db_var, db_default)
    user = os.environ.get(user_var, "root")
    password = os.environ.get(password_var, "payment123")
    # Supply the password on stdin, never in an argv list or saved snapshot.
    script = 'IFS= read -r MYSQL_PWD; export MYSQL_PWD; exec mysql -u "$1" -D "$2" -N -B --raw -e "$3"'
    return _run(["docker", "exec", "-i", container, "sh", "-c", script, "sh", user, database, sql], input_text=password + "\n")


def _rows(role, sql, fields):
    output = _mysql(role, sql)
    rows = []
    for number, line in enumerate(output.splitlines(), 1):
        values = line.split("\t")
        if len(values) != len(fields):
            raise ValueError(f"{role} query row {number}: expected {len(fields)} columns, got {len(values)}")
        row = {}
        for field, value in zip(fields, values):
            # mysql -N -B --raw prints SQL NULL as the literal "NULL".
            if value in ("\\N", "NULL"):
                row[field] = None
            elif field in INT_FIELDS or field == "orderId" and role == "product":
                row[field] = int(value)
            else:
                row[field] = value
        rows.append(row)
    return rows


def _redis(container, key):
    return _run(["docker", "exec", container, "redis-cli", "-c", "--raw", "GET", key]).rstrip("\n")


def _stock_values(container, product_base, product_count):
    """One docker exec; per-key GET also works when stock keys span cluster slots."""
    script = ('set -e; id="$1"; last="$2"; '
              'while [ "$id" -le "$last" ]; do '
              'redis-cli -c --raw GET "stock:{$id}"; '
              'id=$((id + 1)); done')
    output = _run(["docker", "exec", container, "sh", "-c", script, "sh",
                   str(product_base), str(product_base + product_count - 1)])
    values = output.splitlines()
    if len(values) != product_count:
        raise ValueError(f"redis stock read returned {len(values)} values for {product_count} products")
    return values


def _idempotency_clue(key):
    value = _redis(os.environ.get("REDIS_DEDUPE_CONTAINER", "payment-redis-dedupe"), f"idem:{key}")
    if not value:
        return {"state": "ABSENT"}
    if value == "__IN_PROGRESS__":
        return {"state": "MARKER"}
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError:
        return {"state": "UNREADABLE"}
    order_id = parsed.get("orderId") if isinstance(parsed, dict) else None
    return {"state": "RESULT", "orderId": order_id if isinstance(order_id, str) else None}


def capture(product_base=1000, product_count=100, attempts_report=None):
    if product_base < 1 or product_count < 1:
        raise ValueError("product range must be positive")
    last = product_base + product_count - 1
    snapshot = {"schemaVersion": 1, "capturedAt": datetime.now(timezone.utc).isoformat(),
                "productBase": product_base, "productCount": product_count}
    for name, (role, table, columns, fields) in TABLES.items():
        where = " WHERE processed_at IS NULL" if name == "pgOutbox" else ""
        projection = ", ".join(f"`{column}`" for column in columns)
        snapshot[name] = _rows(role, f"SELECT {projection} FROM {table}{where} ORDER BY {columns[0]}", fields)

    stock_rows = _rows("product", f"SELECT product_id, quantity FROM stock WHERE product_id BETWEEN {product_base} AND {last} ORDER BY product_id", ["productId", "quantity"])
    by_product = {row["productId"]: row["quantity"] for row in stock_rows}
    stock_container = os.environ.get("REDIS_STOCK_CONTAINER", "payment-redis-stock")
    snapshot["products"] = {}
    for product_id, value in zip(range(product_base, last + 1),
                                 _stock_values(stock_container, product_base, product_count)):
        snapshot["products"][str(product_id)] = {
            "db": by_product.get(product_id), "redis": int(value) if value else None,
        }

    snapshot["idempotency"] = {}
    if attempts_report is not None:
        report = json.loads(Path(attempts_report).read_text())
        for attempt in report["attempts"]:
            if attempt.get("orderId"):
                continue
            key = attempt.get("idempotencyKey")
            if key and key not in snapshot["idempotency"]:
                snapshot["idempotency"][key] = _idempotency_clue(key)
    return snapshot


def verify(report, before, after):
    """Compare one run's attempts with durable rows and before/after inventory."""
    failures, unknown, pending = [], [], []
    if report.get("validityIssues"):
        pending.append("attempt report has validity issues; inspect source report")
    attempts = report.get("attempts")
    if not isinstance(attempts, list) or not attempts:
        raise ValueError("attempts report must contain a nonempty attempts list")
    for snapshot in (before, after):
        if snapshot.get("schemaVersion") != 1:
            raise ValueError("unsupported snapshot schema")
    if set(before["products"]) != set(after["products"]):
        raise ValueError("before/after product ranges differ")
    _global_residue(before, "before", pending, failures)
    _global_residue(after, "after", pending, failures)

    before_orders = {event["orderId"] for event in before["paymentEvents"]}
    events = {}
    for event in after["paymentEvents"]:
        if event["orderId"] in events:
            failures.append(f"duplicate payment_event: {event['orderId']}")
        events[event["orderId"]] = event
    order_rows = {}
    hold_rows = {}
    hold_products_by_order = {}
    history_rows = {}
    pg_rows = {}
    for row in after["paymentOrders"]:
        order_rows.setdefault(row["orderId"], []).append(row)
    for row in after["holdRecords"]:
        hold_rows.setdefault((row["orderId"], row["productId"]), []).append(row)
        hold_products_by_order.setdefault(row["orderId"], set()).add(row["productId"])
    for row in after["paymentHistories"]:
        history_rows.setdefault(row["orderId"], []).append(row)
    for row in after["pgInbox"]:
        pg_rows.setdefault(row["orderId"], []).append(row)
    stock_commits = {}
    for row in after["stockCommitDedupe"]:
        stock_commits.setdefault(row["eventUuid"], []).append(row)

    done_quantity = {key: 0 for key in before["products"]}
    uncertain_products = set()
    known_orders = set()
    for attempt in attempts:
        attempt_id = attempt.get("attemptId", "<missing-attempt-id>")
        order_id = attempt.get("orderId")
        if not order_id:
            key = attempt.get("idempotencyKey")
            clue = after.get("idempotency", {}).get(key, {})
            mapped = clue.get("orderId") if clue.get("state") == "RESULT" else None
            if mapped and mapped in events:
                order_id = mapped
            else:
                reason = "Redis result has no matching DB payment_event" if mapped else "no durable order ID; Redis absence/marker is not proof of rejection"
                unknown.append({"attemptId": attempt_id, "redisState": clue.get("state", "NOT_CAPTURED"), "reason": reason})
                uncertain_products.add(str(attempt.get("productId")))
                continue
        if order_id in known_orders:
            failures.append(f"duplicate run orderId: {order_id}")
            continue
        known_orders.add(order_id)
        if order_id in before_orders:
            failures.append(f"order existed before run: {order_id}")
        event = events.get(order_id)
        if event is None:
            failures.append(f"missing payment_event: {order_id}")
            continue
        status = event["status"]
        observed = attempt.get("outcome")
        if observed in ("DONE", "FAILED") and status in TERMINAL_EVENT_STATUSES and observed != status:
            failures.append(f"observed {observed} conflicts with payment_event {status}: {order_id}")
        rows = order_rows.get(order_id, [])
        inboxes = pg_rows.get(order_id, [])
        if len(inboxes) > 1:
            failures.append(f"pg_inbox row count != 1: {order_id}/{len(inboxes)}")
        if status == "DONE":
            if len(inboxes) != 1:
                failures.append(f"DONE requires one pg_inbox APPROVED: {order_id}/{len(inboxes)}")
            elif inboxes[0]["status"] != "APPROVED":
                failures.append(f"DONE pg_inbox not APPROVED: {order_id}/{inboxes[0]['status']}")
            if len(inboxes) == 1 and rows:
                try:
                    order_amount = sum((Decimal(str(row["amount"])) for row in rows), Decimal(0))
                    pg_amount = Decimal(str(inboxes[0]["amount"]))
                    if not order_amount.is_finite() or not pg_amount.is_finite() or order_amount != pg_amount:
                        failures.append(f"DONE pg_inbox amount mismatch: {order_id}/{pg_amount}/{order_amount}")
                except (InvalidOperation, KeyError, TypeError):
                    failures.append(f"DONE pg_inbox/order amount invalid: {order_id}")
        elif status in ("FAILED", "EXPIRED") and any(row["status"] == "APPROVED" for row in inboxes):
            failures.append(f"{status} payment_event with pg_inbox APPROVED: {order_id}")
        requested_product = attempt.get("productId")
        if not rows:
            failures.append(f"missing payment_order: {order_id}")
        elif len(rows) != 1:
            failures.append(f"payment_order row count != 1: {order_id}/{len(rows)}")
        for row in rows:
            if row.get("paymentEventId") != event.get("id"):
                failures.append(f"payment_order paymentEventId mismatch: {order_id}/{row.get('paymentEventId')}/{event.get('id')}")
            if row["productId"] != requested_product or row["quantity"] != 1:
                failures.append(f"payment_order differs from requested product/quantity: {order_id}/{row['productId']}/{row['quantity']}")
        for hold_product in hold_products_by_order.get(order_id, set()):
            if hold_product != requested_product:
                failures.append(f"extra hold product: {order_id}/{hold_product}")
        if status == "DONE":
            product_key = str(requested_product)
            if product_key not in done_quantity:
                failures.append(f"DONE requested product outside snapshot range: {order_id}/{requested_product}")
            else:
                done_quantity[product_key] += 1
            expected_uuid = _stock_commit_uuid(order_id, requested_product)
            commit_rows = stock_commits.get(expected_uuid, [])
            if len(commit_rows) != 1:
                failures.append(f"stock commit dedupe row count != 1: {order_id}/{requested_product}/{len(commit_rows)}")
            for commit in commit_rows:
                # product-service's legacy order_id is nullable BIGINT, whereas
                # payment orderId is a UUID string. Join only by derived eventUuid.
                if (commit.get("productId") not in (None, requested_product)
                        or commit.get("quantity") not in (None, 1)):
                    failures.append(f"stock commit dedupe differs from requested order: {order_id}/{requested_product}")
        if status == "QUARANTINED":
            failures.append(f"quarantined order: {order_id}")
        elif status not in ("DONE", "FAILED", "EXPIRED"):
            pending.append(f"nonterminal order {order_id}: {status}")
            uncertain_products.update(str(row["productId"]) for row in rows)
        if status in ("DONE", "FAILED", "EXPIRED") and not any(h.get("currentStatus") == status for h in history_rows.get(order_id, [])):
            failures.append(f"missing terminal payment_history: {order_id}/{status}")
        if status == "DONE" and not rows:
            # A missing order row must never make expected stock look correct.
            if not hold_rows.get((order_id, attempt.get("productId")), []):
                failures.append(f"missing hold for DONE: {order_id}/{attempt.get('productId')}")
            continue
        product_ids = set()
        for row in rows:
            product_id = row["productId"]
            pair = (order_id, product_id)
            if product_id in product_ids:
                failures.append(f"duplicate payment_order product: {order_id}/{product_id}")
            product_ids.add(product_id)
            if status == "DONE":
                if row.get("status") != "SUCCESS":
                    failures.append(f"DONE payment_order not SUCCESS: {order_id}/{product_id}")
                if str(product_id) not in done_quantity:
                    failures.append(f"DONE product outside snapshot range: {order_id}/{product_id}")
            holds = hold_rows.get(pair, [])
            if len(holds) > 1:
                failures.append(f"duplicate hold: {order_id}/{product_id}")
            if status == "DONE" and not holds:
                failures.append(f"missing hold for DONE: {order_id}/{product_id}")
            for hold in holds:
                if hold["quantity"] != 1 or hold["quantity"] != row["quantity"]:
                    failures.append(f"hold quantity mismatch with request/order: {order_id}/{product_id}")
                if hold["status"] == "NOISE":
                    pending.append(f"NOISE hold: {order_id}/{product_id}")
                    uncertain_products.add(str(product_id))
                elif status == "DONE" and hold["status"] != "COMMITTED":
                    failures.append(f"DONE hold not COMMITTED: {order_id}/{product_id}")
                elif status in ("FAILED", "EXPIRED") and hold["status"] != "REVERTED":
                    failures.append(f"failed/expired hold not REVERTED: {order_id}/{product_id}")
        if status == "DONE" and rows and attempt.get("productId") not in product_ids:
            failures.append(f"attempt product missing from order: {order_id}/{attempt.get('productId')}")

    unattributed = sorted(set(events) - before_orders - known_orders)
    if unattributed:
        pending.append(f"new payment_event rows not attributable to run attempts: {len(unattributed)}")

    products = {}
    for product_id, initial in before["products"].items():
        final = after["products"][product_id]
        start = initial.get("db")
        expected = start - done_quantity[product_id] if isinstance(start, int) else None
        products[product_id] = {"beforeDb": start, "beforeRedis": initial.get("redis"),
                                "doneQuantity": done_quantity[product_id], "expected": expected,
                                "afterDb": final.get("db"), "afterRedis": final.get("redis")}
        if start is None or initial.get("redis") is None or start != initial.get("redis"):
            failures.append(f"invalid initial stock: {product_id}")
        if expected is None or final.get("db") != expected or final.get("redis") != expected:
            if product_id in uncertain_products:
                pending.append(f"stock equality undecidable with unresolved orders: {product_id}")
            else:
                failures.append(f"stock mismatch: {product_id}")
    verdict = "FAIL" if failures else "INCONCLUSIVE" if unknown or pending else "PASS"
    return {"schemaVersion": 1, "runId": report.get("runId"), "verdict": verdict,
            "counts": {"attempts": len(attempts), "knownOrders": len(known_orders),
                       "unknownAcceptance": len(unknown), "unattributedNewOrders": len(unattributed),
                       "pending": len(pending), "failures": len(failures)},
            "products": products, "failures": failures, "pending": pending,
            "unknownAcceptance": unknown, "unattributedNewOrders": unattributed}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    snapshot = commands.add_parser("snapshot", help="capture allowlisted DB and Redis state")
    snapshot.add_argument("--output", required=True, type=Path)
    snapshot.add_argument("--product-base", type=int, default=1000)
    snapshot.add_argument("--product-count", type=int, default=100)
    snapshot.add_argument("--attempts-report", type=Path)
    check = commands.add_parser("verify", help="reconcile one run against two snapshots")
    check.add_argument("--attempts-report", required=True, type=Path)
    check.add_argument("--before", required=True, type=Path)
    check.add_argument("--after", required=True, type=Path)
    check.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.command == "snapshot":
        result = capture(args.product_base, args.product_count, args.attempts_report)
    else:
        result = verify(json.loads(args.attempts_report.read_text()),
                        json.loads(args.before.read_text()), json.loads(args.after.read_text()))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(f"{args.command}: {args.output}" + (f" {result['verdict']}" if args.command == "verify" else ""))


if __name__ == "__main__":
    main()
