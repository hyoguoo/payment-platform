import unittest

from scripts.quality.results import analyze


def attempt(number, *, outcome="DONE", confirm_ms=100, done_ms=1000,
            checkout_status=201, confirm_status=202):
    start = 1_000_000 + number * 100
    key = f"quality-run-{number}"
    common = dict(runId="run", attemptId=key, idempotencyKey=key,
                  productId=1000 + number % 100, startMs=start)
    begin = dict(common, kind="start")
    end = dict(common, kind="finish", orderId=f"order-{number}",
               confirmStartMs=start + 100 if confirm_ms is not None else None,
               confirmEndMs=start + 100 + confirm_ms if confirm_ms is not None else None,
               doneMs=start + done_ms if outcome == "DONE" else None,
               finishedMs=start + 2000, outcome=outcome,
               checkoutStatus=checkout_status, confirmStatus=confirm_status,
               apiErrors=[], pollCount=2)
    return begin, end


def summary(iterations=1, dropped=0):
    return {"metrics": {"iterations": {"count": iterations},
                        "dropped_iterations": {"count": dropped}}}


class ResultsTest(unittest.TestCase):
    def test_clean_run_passes(self):
        start, finish = attempt(0)
        report = analyze([start, finish], summary(), "run", 1, 1)
        self.assertEqual(report["verdict"], "PASS")
        self.assertEqual(report["counts"]["fastDone"], 1)
        self.assertEqual(report["counts"]["eventualDone"], 1)
        self.assertEqual(report["confirmP95Ms"], 100)
        self.assertEqual(report["attempts"][0]["idempotencyKey"], "quality-run-0")

    def test_failed_and_timeout_stay_in_denominator(self):
        records = []
        for number, outcome in enumerate(("DONE", "FAILED", "TIMEOUT")):
            records.extend(attempt(number, outcome=outcome))
        report = analyze(records, summary(3), "run", 3, 1)
        self.assertEqual(report["counts"]["started"], 3)
        self.assertEqual(report["counts"]["fastDone"], 1)
        self.assertEqual(report["counts"]["eventualDone"], 1)
        self.assertEqual(report["verdict"], "FAIL")

    def test_confirm_error_then_done_counts_business_success_and_api_error(self):
        start, finish = attempt(0, confirm_status=503)
        finish["apiErrors"] = ["confirm_http_503"]
        report = analyze([start, finish], summary(), "run", 1, 1)
        self.assertEqual(report["verdict"], "PASS")
        self.assertEqual(report["counts"]["apiErrorAttempts"], 1)
        self.assertEqual(report["confirmP95Ms"], 100)

    def test_missing_finish_and_dropped_are_not_success(self):
        start, _ = attempt(0)
        report = analyze([start], summary(1, 1), "run", 2, 1)
        self.assertEqual(report["verdict"], "INCONCLUSIVE")
        self.assertEqual(report["counts"]["missingFinish"], 1)
        self.assertEqual(report["counts"]["dropped"], 1)
        self.assertEqual(report["counts"]["eventualDone"], 0)

    def test_duplicate_and_mismatched_records_invalidate(self):
        start, finish = attempt(0)
        report = analyze([start, start, finish], summary(), "run", 1, 1)
        self.assertEqual(report["verdict"], "INCONCLUSIVE")
        self.assertTrue(any("duplicate" in issue for issue in report["validityIssues"]))

    def test_checkout_error_is_failure_and_unresolved_acceptance(self):
        start, finish = attempt(0, outcome="CHECKOUT_ERROR", confirm_ms=None,
                                checkout_status=503, confirm_status=None)
        finish["orderId"] = None
        finish["apiErrors"] = ["checkout_http_503"]
        report = analyze([start, finish], summary(), "run", 1, 1)
        self.assertEqual(report["counts"]["eventualDone"], 0)
        self.assertEqual(report["counts"]["acceptanceUnknown"], 1)
        self.assertEqual(report["verdict"], "INCONCLUSIVE")

    def test_deadline_and_confirm_start_are_strict(self):
        start, finish = attempt(0, done_ms=60_001)
        finish["finishedMs"] = finish["doneMs"]
        report = analyze([start, finish], summary(), "run", 1, 1)
        self.assertEqual(report["counts"]["eventualDone"], 0)
        self.assertEqual(report["verdict"], "FAIL")
        start, finish = attempt(0)
        finish["confirmStartMs"] = None
        report = analyze([start, finish], summary(), "run", 1, 1)
        self.assertEqual(report["counts"]["fastDone"], 0)

    def test_exact_boundary_attempt_is_in_denominator_and_settlement(self):
        first = attempt(0)
        boundary = attempt(1)
        boundary[0]["startMs"] = first[0]["startMs"] + 1000
        boundary[1]["startMs"] = boundary[0]["startMs"]
        boundary[1]["confirmStartMs"] = boundary[0]["startMs"] + 100
        boundary[1]["confirmEndMs"] = boundary[0]["startMs"] + 200
        boundary[1]["doneMs"] = boundary[0]["startMs"] + 1000
        boundary[1]["finishedMs"] = boundary[0]["startMs"] + 2000
        report = analyze([*first, *boundary], summary(2), "run", 1, 1)
        self.assertEqual(report["verdict"], "PASS")
        self.assertEqual(report["counts"]["started"], 2)
        self.assertEqual(report["counts"]["extraStarts"], 1)
        self.assertEqual(report["counts"]["boundaryStarts"], 1)
        self.assertEqual(sum(item["inPlannedWindow"] for item in report["attempts"]), 1)

    def test_k6_summary_values_and_one_extra_inside_window(self):
        records = []
        for number in range(11):
            start, finish = attempt(number)
            start["startMs"] = 1_000_000 + number * 99
            finish["startMs"] = start["startMs"]
            finish["confirmStartMs"] = start["startMs"] + 100
            finish["confirmEndMs"] = start["startMs"] + 200
            finish["doneMs"] = start["startMs"] + 1000
            finish["finishedMs"] = start["startMs"] + 2000
            start["scenarioStartMs"] = finish["scenarioStartMs"] = 1_000_000
            records.extend((start, finish))
        k6_summary = {"metrics": {"iterations": {"values": {"count": 11}},
                                  "dropped_iterations": {"values": {"count": 0}}}}
        report = analyze(records, k6_summary, "run", 10, 1)
        self.assertEqual(report["verdict"], "PASS")
        self.assertEqual(report["counts"]["started"], 11)
        self.assertEqual(report["counts"]["extraStarts"], 1)
        self.assertEqual(report["counts"]["boundaryStarts"], 0)

    def test_missing_summary_cannot_pass(self):
        start, finish = attempt(0)
        report = analyze([start, finish], {}, "run", 1, 1)
        self.assertEqual(report["verdict"], "INCONCLUSIVE")


if __name__ == "__main__":
    unittest.main()
