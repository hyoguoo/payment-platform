"""Run-scoped settlement fixtures; no Docker services are needed."""

import copy
import hashlib
import unittest
import uuid
from unittest.mock import patch

from settlement import _rows, _stock_values, verify


def stock_uuid(order_id, product_id):
    seed = f"stock-commit:{order_id}:{product_id}".encode()
    return str(uuid.UUID(bytes=hashlib.md5(seed).digest(), version=3))


def fixture():
    before = {
        "schemaVersion": 1,
        "products": {"1000": {"db": 10, "redis": 10}},
        "paymentEvents": [], "paymentOrders": [], "holdRecords": [],
        "paymentHistories": [], "paymentOutbox": [], "pgInbox": [],
        "pgOutbox": [], "stockCommitDedupe": [], "idempotency": {},
    }
    after = copy.deepcopy(before)
    after["products"]["1000"] = {"db": 9, "redis": 9}
    after["paymentEvents"] = [{"id": 1, "orderId": "o1", "status": "DONE"}]
    after["paymentOrders"] = [{"id": 1, "paymentEventId": 1, "orderId": "o1", "productId": 1000,
                               "quantity": 1, "amount": "1000.00", "status": "SUCCESS"}]
    after["pgInbox"] = [{"id": 1, "orderId": "o1", "status": "APPROVED", "amount": "1000"}]
    after["holdRecords"] = [{"id": 1, "orderId": "o1", "productId": 1000,
                              "quantity": 1, "status": "COMMITTED"}]
    after["paymentHistories"] = [{"id": 1, "orderId": "o1", "currentStatus": "DONE"}]
    after["stockCommitDedupe"] = [{"eventUuid": stock_uuid("o1", 1000),
                                    "orderId": None, "productId": None,
                                    "quantity": None}]
    report = {"runId": "r1", "attempts": [{"attemptId": "a1", "idempotencyKey": "i1",
                "productId": 1000, "startMs": 1, "orderId": "o1", "confirmStartMs": 2,
                "checkoutStatus": 201, "outcome": "DONE"}]}
    return report, before, after


class SettlementTest(unittest.TestCase):
    def test_mysql_decimal_amount_stays_exact_text(self):
        with patch("settlement._mysql", return_value="1\to1\t1000\t1\t1234.00\tSUCCESS\n"):
            rows = _rows("payment", "SELECT ...", ["id", "orderId", "productId", "quantity", "amount", "status"])
        self.assertEqual(rows[0]["amount"], "1234.00")

    def test_mysql_raw_null_is_none_even_for_nullable_integer(self):
        with patch("settlement._mysql", return_value="uuid-1\tNULL\t1000\t1\tNULL\n"):
            rows = _rows("product", "SELECT ...", ["eventUuid", "orderId", "productId", "quantity", "createdAt"])
        self.assertIsNone(rows[0]["orderId"])
        self.assertIsNone(rows[0]["createdAt"])

    def test_stock_values_single_docker_exec_preserves_missing_key(self):
        with patch("settlement._run", return_value="9\n\n7\n") as run:
            self.assertEqual(_stock_values("stock-container", 1000, 3), ["9", "", "7"])
            self.assertEqual(run.call_count, 1)

    def test_done_stock_and_hold_pass(self):
        report, before, after = fixture()
        result = verify(report, before, after)
        self.assertEqual(result["verdict"], "PASS")
        self.assertEqual(result["products"]["1000"]["expected"], 9)

    def test_observed_done_conflicts_with_failed_durable_outcome(self):
        report, before, after = fixture()
        after["paymentEvents"][0]["status"] = "FAILED"
        after["paymentOrders"][0]["status"] = "FAIL"
        after["paymentHistories"][0]["currentStatus"] = "FAILED"
        after["holdRecords"][0]["status"] = "REVERTED"
        after["pgInbox"][0]["status"] = "FAILED"
        after["stockCommitDedupe"] = []
        after["products"]["1000"] = {"db": 10, "redis": 10}
        result = verify(report, before, after)
        self.assertEqual(result["verdict"], "FAIL")
        self.assertTrue(any("observed DONE" in item for item in result["failures"]))

    def test_observed_failed_conflicts_with_done_durable_outcome(self):
        report, before, after = fixture()
        report["attempts"][0]["outcome"] = "FAILED"
        result = verify(report, before, after)
        self.assertEqual(result["verdict"], "FAIL")
        self.assertTrue(any("observed FAILED" in item for item in result["failures"]))

    def test_timeout_can_settle_as_done(self):
        report, before, after = fixture()
        report["attempts"][0]["outcome"] = "TIMEOUT"
        self.assertEqual(verify(report, before, after)["verdict"], "PASS")

    def test_equal_decimal_amount_formats_pass(self):
        report, before, after = fixture()
        after["pgInbox"][0]["amount"] = "1000.0"
        self.assertEqual(verify(report, before, after)["verdict"], "PASS")

    def test_done_requires_matching_pg_approval_and_amount(self):
        report, before, after = fixture()
        for inbox in ([], [{"id": 1, "orderId": "o1", "status": "FAILED", "amount": "1000"}],
                      [{"id": 1, "orderId": "o1", "status": "APPROVED", "amount": "9999"}]):
            case = copy.deepcopy(after)
            case["pgInbox"] = inbox
            result = verify(report, before, case)
            self.assertEqual(result["verdict"], "FAIL")
            self.assertTrue(any("pg_inbox" in item for item in result["failures"]))

    def test_failed_payment_cannot_have_pg_approval(self):
        report, before, after = fixture()
        report["attempts"][0]["outcome"] = "FAILED"
        after["paymentEvents"][0]["status"] = "FAILED"
        after["paymentOrders"][0]["status"] = "FAIL"
        after["paymentHistories"][0]["currentStatus"] = "FAILED"
        after["holdRecords"][0]["status"] = "REVERTED"
        after["stockCommitDedupe"] = []
        after["products"]["1000"] = {"db": 10, "redis": 10}
        result = verify(report, before, after)
        self.assertEqual(result["verdict"], "FAIL")
        self.assertTrue(any("pg_inbox APPROVED" in item for item in result["failures"]))

    def test_expired_payment_cannot_have_pg_approval(self):
        report, before, after = fixture()
        report["attempts"][0]["outcome"] = "TIMEOUT"
        after["paymentEvents"][0]["status"] = "EXPIRED"
        after["paymentOrders"][0]["status"] = "EXPIRED"
        after["paymentHistories"][0]["currentStatus"] = "EXPIRED"
        after["holdRecords"][0]["status"] = "REVERTED"
        after["stockCommitDedupe"] = []
        after["products"]["1000"] = {"db": 10, "redis": 10}
        result = verify(report, before, after)
        self.assertEqual(result["verdict"], "FAIL")
        self.assertTrue(any("pg_inbox APPROVED" in item for item in result["failures"]))

    def test_done_pg_inbox_pending_is_inconclusive_or_fail(self):
        report, before, after = fixture()
        after["pgInbox"][0]["status"] = "IN_PROGRESS"
        self.assertNotEqual(verify(report, before, after)["verdict"], "PASS")

    def test_order_must_belong_to_payment_event(self):
        report, before, after = fixture()
        after["paymentOrders"][0]["paymentEventId"] = 2
        result = verify(report, before, after)
        self.assertEqual(result["verdict"], "FAIL")
        self.assertTrue(any("paymentEventId mismatch" in item for item in result["failures"]))

    def test_both_db_and_redis_equally_wrong_fails(self):
        report, before, after = fixture()
        after["products"]["1000"] = {"db": 10, "redis": 10}
        self.assertEqual(verify(report, before, after)["verdict"], "FAIL")

    def test_missing_order_and_hold_are_visible(self):
        report, before, after = fixture()
        after["paymentOrders"] = []
        after["holdRecords"] = []
        result = verify(report, before, after)
        self.assertEqual(result["verdict"], "FAIL")
        self.assertTrue(any("missing payment_order" in item for item in result["failures"]))
        self.assertTrue(any("missing hold" in item for item in result["failures"]))

    def test_pending_quarantine_noise_block_pass(self):
        report, before, after = fixture()
        for status, expected in (("IN_PROGRESS", "INCONCLUSIVE"),
                                 ("QUARANTINED", "FAIL")):
            case = copy.deepcopy(after)
            case["paymentEvents"][0]["status"] = status
            self.assertEqual(verify(report, before, case)["verdict"], expected)
        case = copy.deepcopy(after)
        case["holdRecords"][0]["status"] = "NOISE"
        self.assertNotEqual(verify(report, before, case)["verdict"], "PASS")

    def test_failed_hold_must_be_reverted_but_no_hold_is_valid(self):
        report, before, after = fixture()
        report["attempts"][0]["outcome"] = "FAILED"
        after["paymentEvents"][0]["status"] = "FAILED"
        after["paymentOrders"][0]["status"] = "FAIL"
        after["paymentHistories"][0]["currentStatus"] = "FAILED"
        after["products"]["1000"] = {"db": 10, "redis": 10}
        after["pgInbox"][0]["status"] = "FAILED"
        after["holdRecords"][0]["status"] = "COMMITTED"
        self.assertEqual(verify(report, before, after)["verdict"], "FAIL")
        after["holdRecords"] = []
        self.assertEqual(verify(report, before, after)["verdict"], "PASS")

    def test_expired_mapping_does_not_imply_no_checkout(self):
        report, before, after = fixture()
        report["attempts"][0]["orderId"] = None
        report["attempts"][0]["checkoutStatus"] = 503
        after["idempotency"] = {"i1": {"state": "ABSENT"}}
        result = verify(report, before, after)
        self.assertEqual(result["verdict"], "INCONCLUSIVE")
        self.assertTrue(result["unknownAcceptance"])

    def test_redis_mapping_only_is_not_durable_acceptance(self):
        report, before, after = fixture()
        report["attempts"][0]["orderId"] = None
        report["attempts"][0]["checkoutStatus"] = 503
        after["paymentEvents"] = []
        after["paymentOrders"] = []
        after["holdRecords"] = []
        after["paymentHistories"] = []
        after["products"]["1000"] = {"db": 10, "redis": 10}
        after["idempotency"] = {"i1": {"state": "RESULT", "orderId": "o1"}}
        result = verify(report, before, after)
        self.assertEqual(result["verdict"], "INCONCLUSIVE")
        self.assertTrue(result["unknownAcceptance"])

    def test_redis_mapping_with_db_order_can_reconcile(self):
        report, before, after = fixture()
        report["attempts"][0]["orderId"] = None
        report["attempts"][0]["checkoutStatus"] = 503
        after["idempotency"] = {"i1": {"state": "RESULT", "orderId": "o1"}}
        self.assertEqual(verify(report, before, after)["verdict"], "PASS")

    def test_report_validity_issue_blocks_settlement_pass(self):
        report, before, after = fixture()
        report["validityIssues"] = ["missing finish: 1"]
        self.assertEqual(verify(report, before, after)["verdict"], "INCONCLUSIVE")

    def test_new_event_without_trial_is_unattributed(self):
        report, before, after = fixture()
        after["paymentEvents"].append({"id": 2, "orderId": "o2", "status": "READY"})
        result = verify(report, before, after)
        self.assertEqual(result["verdict"], "INCONCLUSIVE")
        self.assertEqual(result["unattributedNewOrders"], ["o2"])

    def test_request_quantity_one_cannot_be_relabelled_two_everywhere(self):
        report, before, after = fixture()
        after["paymentOrders"][0]["quantity"] = 2
        after["holdRecords"][0]["quantity"] = 2
        after["products"]["1000"] = {"db": 8, "redis": 8}
        result = verify(report, before, after)
        self.assertEqual(result["verdict"], "FAIL")
        self.assertEqual(result["products"]["1000"]["expected"], 9)

    def test_extra_product_row_and_hold_cannot_pass(self):
        report, before, after = fixture()
        before["products"]["1001"] = {"db": 10, "redis": 10}
        after["products"]["1001"] = {"db": 9, "redis": 9}
        after["paymentOrders"].append({"id": 2, "orderId": "o1", "productId": 1001,
                                        "quantity": 1, "status": "SUCCESS"})
        after["holdRecords"].append({"id": 2, "orderId": "o1", "productId": 1001,
                                      "quantity": 1, "status": "COMMITTED"})
        after["stockCommitDedupe"].append({"eventUuid": stock_uuid("o1", 1001),
                                            "orderId": None, "productId": 1001,
                                            "quantity": 1})
        self.assertEqual(verify(report, before, after)["verdict"], "FAIL")

    def test_done_requires_deterministic_stock_commit_uuid(self):
        report, before, after = fixture()
        after["stockCommitDedupe"] = []
        self.assertEqual(verify(report, before, after)["verdict"], "FAIL")

    def test_stock_commit_legacy_numeric_order_id_is_not_join_key(self):
        report, before, after = fixture()
        after["stockCommitDedupe"][0]["orderId"] = 123
        self.assertEqual(verify(report, before, after)["verdict"], "PASS")
        after["stockCommitDedupe"] = [{"eventUuid": "wrong", "orderId": "o1",
                                        "productId": 1000, "quantity": 1}]
        self.assertEqual(verify(report, before, after)["verdict"], "FAIL")

    def test_orphan_noise_hold_and_pending_pg_inbox_block_pass(self):
        report, before, after = fixture()
        after["holdRecords"].append({"id": 2, "orderId": "other", "productId": 1000,
                                      "quantity": 1, "status": "NOISE"})
        self.assertNotEqual(verify(report, before, after)["verdict"], "PASS")
        after["holdRecords"].pop()
        after["pgInbox"].append({"id": 1, "orderId": "other", "status": "PENDING"})
        self.assertNotEqual(verify(report, before, after)["verdict"], "PASS")

    def test_preexisting_and_after_run_unfinished_work_block_pass(self):
        report, before, after = fixture()
        before["paymentEvents"].append({"id": 2, "orderId": "old", "status": "IN_PROGRESS"})
        after["paymentEvents"].append({"id": 2, "orderId": "old", "status": "IN_PROGRESS"})
        self.assertNotEqual(verify(report, before, after)["verdict"], "PASS")
        before["paymentEvents"].clear()
        after["paymentEvents"].pop()
        after["paymentOutbox"].append({"id": 1, "orderId": "other", "status": "IN_FLIGHT"})
        self.assertNotEqual(verify(report, before, after)["verdict"], "PASS")
        after["paymentOutbox"].clear()
        after["pgOutbox"].append({"id": 1, "topic": "payment-result", "processedAt": None})
        self.assertNotEqual(verify(report, before, after)["verdict"], "PASS")


if __name__ == "__main__":
    unittest.main()
