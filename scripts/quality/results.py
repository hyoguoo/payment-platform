#!/usr/bin/env python3
"""시도 JSONL과 k6 summary에서 한 실행의 사용자 품질을 재계산한다.

콘솔 원자료는 ``k6 run --log-format raw --console-output <path>``로 저장한다.
시간은 Unix epoch milliseconds이며, 목표의 분모는 start 이벤트 전부다.
"""

import argparse
import json
import math
from pathlib import Path


def _count_metric(summary, name, issues):
    metric = summary.get("metrics", {}).get(name)
    values = metric.get("values", metric) if isinstance(metric, dict) else None
    if not isinstance(values, dict) or not isinstance(values.get("count"), (int, float)):
        issues.append(f"missing k6 metric: {name}")
        return None
    return int(values["count"])


def _percentile(values, percentage):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[math.ceil(len(ordered) * percentage / 100) - 1]


def analyze(events, summary, run_id, rate, duration_seconds, product_id_base=1000):
    """검증 결과와 주문 대조용 시도 목록을 반환한다. 기존 벤치마크 지표와 합치지 않는다."""
    if not run_id or rate <= 0 or duration_seconds <= 0:
        raise ValueError("run_id, rate, duration_seconds must be positive/nonempty")
    expected = rate * duration_seconds
    issues = []
    starts = {}
    finishes = {}
    for number, event in enumerate(events, 1):
        if not isinstance(event, dict):
            issues.append(f"event {number}: not an object")
            continue
        if event.get("runId") != run_id:
            issues.append(f"event {number}: runId mismatch")
            continue
        attempt_id = event.get("attemptId")
        kind = event.get("kind")
        if not isinstance(attempt_id, str) or not attempt_id:
            issues.append(f"event {number}: missing attemptId")
            continue
        target = starts if kind == "start" else finishes if kind == "finish" else None
        if target is None:
            issues.append(f"event {number}: invalid kind")
            continue
        if attempt_id in target:
            issues.append(f"duplicate {kind}: {attempt_id}")
            continue
        target[attempt_id] = event

    missing_finish = set(starts) - set(finishes)
    orphan_finish = set(finishes) - set(starts)
    if missing_finish:
        issues.append(f"missing finish: {len(missing_finish)}")
    if orphan_finish:
        issues.append(f"finish without start: {len(orphan_finish)}")

    iterations = _count_metric(summary, "iterations", issues)
    dropped = _count_metric(summary, "dropped_iterations", issues)
    if iterations is not None and iterations != len(starts):
        issues.append(f"iterations/start mismatch: {iterations}/{len(starts)}")
    actual_start = min((item["startMs"] for item in starts.values()
                        if isinstance(item.get("startMs"), int)), default=None)
    scenario_starts = {item.get("scenarioStartMs") for item in starts.values()
                       if item.get("scenarioStartMs") is not None}
    if len(scenario_starts) > 1 or any(not isinstance(value, int) for value in scenario_starts):
        issues.append("inconsistent scenarioStartMs")
    one_scenario_start = next(iter(scenario_starts)) if len(scenario_starts) == 1 else None
    t0 = one_scenario_start if isinstance(one_scenario_start, int) else actual_start
    t1 = t0 + duration_seconds * 1000 if t0 is not None else None
    boundary_starts = sum(1 for item in starts.values() if t1 is not None
                          and isinstance(item.get("startMs"), int)
                          and item["startMs"] >= t1)
    extra_starts = max(0, len(starts) - expected)
    # k6 2.3.0 can start one iteration at the duration boundary. It remains in
    # the quality denominator and in the settlement list; only one is allowed.
    if len(starts) not in (expected, expected + 1):
        issues.append(f"expected/started mismatch: {expected}/{len(starts)}")
    if boundary_starts > 1:
        issues.append(f"more than one boundary start: {boundary_starts}")
    if dropped is not None and dropped:
        issues.append(f"dropped iterations: {dropped}")

    keys = set()
    orders = set()
    normalized = []
    confirm_durations = []
    fast_done = eventual_done = api_error_attempts = acceptance_unknown = 0
    outcomes = {}
    for attempt_id, start in sorted(starts.items(), key=lambda item: item[1].get("startMs", 0)):
        finish = finishes.get(attempt_id, {})
        in_planned_window = t1 is not None and isinstance(start.get("startMs"), int) and start["startMs"] < t1
        key = start.get("idempotencyKey")
        product_id = start.get("productId")
        started_at = start.get("startMs")
        if not isinstance(key, str) or not key:
            issues.append(f"{attempt_id}: missing idempotency key")
        elif key in keys:
            issues.append(f"duplicate idempotency key: {key}")
        keys.add(key)
        if not isinstance(product_id, int) or not product_id_base <= product_id < product_id_base + 100:
            issues.append(f"{attempt_id}: invalid productId")
        if not isinstance(started_at, int):
            issues.append(f"{attempt_id}: invalid startMs")
            started_at = 0
        if finish:
            for field in ("idempotencyKey", "productId", "startMs"):
                if finish.get(field) != start.get(field):
                    issues.append(f"{attempt_id}: {field} mismatch")

        confirm_start = finish.get("confirmStartMs")
        confirm_end = finish.get("confirmEndMs")
        done_at = finish.get("doneMs")
        finished_at = finish.get("finishedMs")
        order_id = finish.get("orderId")
        outcome = finish.get("outcome", "MISSING_FINISH")
        if finish and (not isinstance(finished_at, int) or finished_at < started_at):
            issues.append(f"{attempt_id}: invalid finishedMs")
        if confirm_start is not None or confirm_end is not None:
            if (not isinstance(confirm_start, int) or not isinstance(confirm_end, int)
                    or confirm_start < started_at or confirm_end < confirm_start):
                issues.append(f"{attempt_id}: invalid confirm interval")
            else:
                confirm_durations.append(confirm_end - confirm_start)
        if order_id is not None:
            if not isinstance(order_id, str) or not order_id:
                issues.append(f"{attempt_id}: invalid orderId")
            elif order_id in orders:
                issues.append(f"duplicate orderId: {order_id}")
            orders.add(order_id)
        if outcome == "DONE":
            if not isinstance(done_at, int) or not isinstance(order_id, str):
                issues.append(f"{attempt_id}: DONE lacks observation/order")
            elif done_at < started_at:
                issues.append(f"{attempt_id}: DONE precedes checkout")
            else:
                if done_at - started_at <= 60_000:
                    eventual_done += 1
                    if isinstance(confirm_start, int) and 0 <= done_at - confirm_start <= 5000:
                        fast_done += 1
        elif done_at is not None:
            issues.append(f"{attempt_id}: non-DONE has doneMs")
        if finish.get("apiErrors"):
            api_error_attempts += 1
        if finish and not order_id and finish.get("checkoutStatus") not in (400, 401, 403, 404, 422):
            acceptance_unknown += 1
        outcomes[outcome] = outcomes.get(outcome, 0) + 1
        normalized.append({
            "attemptId": attempt_id, "idempotencyKey": key, "productId": product_id,
            "inPlannedWindow": in_planned_window,
            "startMs": started_at, "orderId": order_id, "confirmStartMs": confirm_start,
            "confirmEndMs": confirm_end, "doneMs": done_at, "finishedMs": finished_at,
            "outcome": outcome, "checkoutStatus": finish.get("checkoutStatus"),
            "confirmStatus": finish.get("confirmStatus"), "apiErrors": finish.get("apiErrors", []),
            "pollCount": finish.get("pollCount", 0),
        })

    if acceptance_unknown:
        issues.append(f"checkout acceptance unknown: {acceptance_unknown}; reconcile idempotency keys")
    if not confirm_durations:
        issues.append("no confirm calls for p95")
    p95 = _percentile(confirm_durations, 95)
    started = len(starts)
    goals = {
        "arrival": "PASS" if started in (expected, expected + 1) and dropped == 0 and iterations == started else "FAIL",
        "confirmP95": "PASS" if p95 is not None and p95 <= 500 else "FAIL",
        "doneWithin5s": "PASS" if started and fast_done / started >= 0.95 else "FAIL",
        "doneWithin60s": "PASS" if started and eventual_done / started >= 0.999 else "FAIL",
    }
    return {
        "schemaVersion": 1, "runId": run_id, "rate": rate,
        "durationSeconds": duration_seconds, "measurementStartMs": t0, "measurementEndMs": t1,
        "actualFirstStartMs": actual_start,
        "actualLastStartMs": max((item.get("startMs") for item in starts.values()
                                  if isinstance(item.get("startMs"), int)), default=None),
        "productIdBase": product_id_base, "counts": {
            "expected": expected, "started": started, "extraStarts": extra_starts,
            "boundaryStarts": boundary_starts,
            "finished": len(starts) - len(missing_finish),
            "missingFinish": len(missing_finish), "orphanFinish": len(orphan_finish),
            "iterations": iterations, "dropped": dropped, "confirmCalls": len(confirm_durations),
            "fastDone": fast_done, "eventualDone": eventual_done,
            "apiErrorAttempts": api_error_attempts, "acceptanceUnknown": acceptance_unknown,
            "outcomes": outcomes,
        },
        "confirmP95Ms": p95,
        "fastDoneRate": fast_done / started if started else 0,
        "eventualDoneRate": eventual_done / started if started else 0,
        "goals": goals, "validityIssues": issues,
        "verdict": "INCONCLUSIVE" if issues else
                   "PASS" if all(value == "PASS" for value in goals.values()) else "FAIL",
        "attempts": normalized,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--attempts", required=True, type=Path)
    parser.add_argument("--summary", required=True, type=Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--rate", type=int, default=10)
    parser.add_argument("--duration-seconds", type=int, default=600)
    parser.add_argument("--product-id-base", type=int, default=1000)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    events = []
    for number, line in enumerate(args.attempts.read_text().splitlines(), 1):
        if not line.strip():
            continue
        try:
            events.append(json.loads(line))
        except json.JSONDecodeError as exc:
            raise SystemExit(f"{args.attempts}:{number}: invalid JSON: {exc}") from exc
    summary = json.loads(args.summary.read_text())
    report = analyze(events, summary, args.run_id, args.rate, args.duration_seconds,
                     args.product_id_base)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"{args.run_id}: {report['verdict']}; {report['counts']['started']}/{report['counts']['expected']} started, "
          f"{report['counts']['fastDone']} fast DONE, {report['counts']['eventualDone']} eventual DONE")


if __name__ == "__main__":
    main()
