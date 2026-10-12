"""Evidence eligibility and fault verdict regressions for the HTML report."""

import json
import tempfile
import unittest
from pathlib import Path

from scripts.quality import report


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def finished_run(root, name="normal-01", *, verdict="PASS", source_changed=False,
                 fault=False, fault_data=None):
    folder = root / name
    save(folder / "result.json", {"schemaVersion": 1, "runId": name, "verdict": "PASS",
                                  "rate": 10, "durationSeconds": 1, "validityIssues": [],
                                  "counts": {"expected": 10, "started": 10, "finished": 10,
                                             "missingFinish": 0, "iterations": 10, "dropped": 0,
                                             "fastDone": 10, "eventualDone": 10,
                                             "acceptanceUnknown": 0},
                                  "goals": {"arrival": "PASS", "confirmP95": "PASS",
                                            "doneWithin5s": "PASS", "doneWithin60s": "PASS"}})
    save(folder / "settlement.json", {"schemaVersion": 1, "runId": name, "verdict": "PASS",
                                      "counts": {"attempts": 10, "knownOrders": 10,
                                                 "unknownAcceptance": 0,
                                                 "unattributedNewOrders": 0,
                                                 "pending": 0, "failures": 0},
                                      "failures": [], "pending": [], "unknownAcceptance": [],
                                      "unattributedNewOrders": []})
    save(folder / "run.json", {"runId": name, "verdict": verdict, "quality": "PASS", "settlement": "PASS",
                               "sourceChanged": source_changed, "fault": fault, "exitCode": 0})
    if fault_data is not None:
        save(folder / "fault.json", fault_data)


class ReportTest(unittest.TestCase):
    def test_incomplete_command_metadata_never_invents_project_or_output_root(self):
        command = {"rate": 1, "durationSeconds": 30}
        for metadata in ({}, {"composeProject": "quality-fixture"},
                         {"outputRoot": "live-drill/quality-fixture"}):
            with self.subTest(metadata=metadata):
                rendered = report.safe_command(command | metadata, "preparation-01")
                self.assertNotIn("python3 scripts/quality/run.py", rendered)
                self.assertIn("기록 부족", rendered)

    def test_old_root_exclusion_overrides_recorded_pass_in_header_and_summary(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "quality-baseline"
            finished_run(root)
            save(root / "evidence-status.json", {"status": "EXCLUDED",
                  "reason": "클린 시작 환경 증거 부족", "supersededBy": "../quality-fixture/report.html"})
            page = report.render(root)
            self.assertIn("evidence-status.json", page)
            self.assertIn("클린 시작 환경 증거 부족", page)
            self.assertIn("EXCLUDED", page.split('class="nav-card" href="#normal-01"')[1].split('</a>')[0])
            self.assertIn("실행별 종합 판정", page)

    def test_clean_environment_shows_freshness_and_escapes_source_values(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "quality-fixture"
            save(root / "clean-environment.json", {
                "project": "<script>alert(1)</script>", "network": "quality-network",
                "freshVolumes": True, "freshNetwork": True, "emptyPaymentState": True,
                "emptyKafka": True, "emptyRedis": True, "volumes": [{"name": "fresh-db"}],
                "previousVolumesPreserved": True})
            page = report.render(root)
            self.assertIn("clean-environment.json", page)
            self.assertIn("fresh-db", page)
            self.assertIn("&lt;script&gt;alert(1)&lt;/script&gt;", page)
            self.assertNotIn("<script>alert(1)</script>", page)

    def test_new_root_links_prior_exclusion_without_excluding_new_run(self):
        with tempfile.TemporaryDirectory() as temp:
            parent = Path(temp)
            save(parent / "previous-run" / "evidence-status.json", {
                "status": "EXCLUDED", "reason": "이전 환경 증거 부족"})
            root = parent / "new-run"
            save(root / "evidence-status.json", {"previousRoot": "previous-run"})
            finished_run(root)
            page = report.render(root)
            self.assertIn("../previous-run/evidence-status.json", page)
            self.assertIn("이전 환경 증거 부족", page)
            self.assertIn("PASS", page.split('class="nav-card" href="#normal-01"')[1].split('</a>')[0])

    def test_empty_new_root_reports_unrun_without_inheriting_or_claiming_old_results(self):
        with tempfile.TemporaryDirectory() as temp:
            parent = Path(temp)
            save(parent / "quality-baseline" / "evidence-status.json", {
                "status": "EXCLUDED", "reason": "과거 실행 제외 사유"})
            page = report.render(parent / "new-run")
            self.assertIn("미실행", page)
            self.assertIn("측정 도구 준비, 새 공식 실측 미실행", page)
            self.assertIn("정상 상태의 두 실행 중 0회를 측정했다", page)
            self.assertIn("아직 측정하지 않았다", page)
            self.assertNotIn("과거 실행 제외 사유", page)
            self.assertNotIn("부하 발생기 보완", page)
            self.assertNotIn("장애 재측정", page)
            self.assertIn("payment 중단·복구 2회", page)

    def test_source_changed_blocks_claim_promotion(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "quality-fixture"
            finished_run(root, source_changed=True)
            page = report.render(root)
            self.assertIn("INCONCLUSIVE", page.split('class="nav-card" href="#normal-01"')[1].split('</a>')[0])
            self.assertIn("측정 중 소스 변경", page)

    def test_normal_pass_needs_complete_matching_result_and_settlement(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "new-run"
            for missing in ("result.json", "settlement.json"):
                with self.subTest(missing=missing):
                    finished_run(root)
                    (root / "normal-01" / missing).unlink()
                    page = report.render(root)
                    card = page.split('class="nav-card" href="#normal-01"')[1].split('</a>')[0]
                    detail = page.split('<section id="normal-01"')[1].split('</section>')[0]
                    self.assertNotIn('chip pass">PASS', card)
                    self.assertNotIn('<h2>정상 부하 1회</h2><span class="chip pass">PASS', detail)
                    self.assertNotIn('이 시험의 결론 <span class="chip pass">PASS', detail)
            for file, value in (("result.json", {"verdict": "FAIL"}),
                                ("settlement.json", {"verdict": "INCONCLUSIVE"}),
                                ("run.json", {"verdict": "PASS", "quality": "INCONCLUSIVE",
                                              "settlement": "PASS"})):
                with self.subTest(file=file):
                    finished_run(root)
                    save(root / "normal-01" / file, value)
                    card = report.render(root).split('class="nav-card" href="#normal-01"')[1].split('</a>')[0]
                    self.assertNotIn('chip pass">PASS', card)

    def test_normal_pass_rejects_verdict_only_or_inconsistent_evidence(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "quality-fixture"
            for filename in ("result.json", "settlement.json"):
                with self.subTest(verdict_only=filename):
                    finished_run(root)
                    save(root / "normal-01" / filename, {"verdict": "PASS"})
                    card = report.render(root).split('class="nav-card" href="#normal-01"')[1].split('</a>')[0]
                    self.assertNotIn('chip pass">PASS', card)
            cases = (
                ("result.json", ("runId",), "another-run"),
                ("settlement.json", ("runId",), "another-run"),
                ("result.json", ("schemaVersion",), 2),
                ("settlement.json", ("schemaVersion",), 2),
                ("result.json", ("validityIssues",), ["incomplete measurement"]),
                ("result.json", ("counts", "started"), "10"),
                ("result.json", ("counts", "acceptanceUnknown"), 1),
                ("settlement.json", ("counts", "attempts"), 9),
                ("settlement.json", ("counts", "unknownAcceptance"), 1),
                ("settlement.json", ("failures",), ["stock mismatch"]),
                ("settlement.json", ("counts", "pending"), 1),
                ("run.json", ("sourceChanged",), None),
                ("run.json", ("exitCode",), None),
            )
            for filename, keys, value in cases:
                with self.subTest(filename=filename, keys=keys):
                    finished_run(root)
                    path = root / "normal-01" / filename
                    payload = json.loads(path.read_text(encoding="utf-8"))
                    field = payload
                    for key in keys[:-1]:
                        field = field[key]
                    field[keys[-1]] = value
                    save(path, payload)
                    card = report.render(root).split('class="nav-card" href="#normal-01"')[1].split('</a>')[0]
                    self.assertNotIn('chip pass">PASS', card)
            finished_run(root)
            for filename in ("run.json", "result.json", "settlement.json"):
                path = root / "normal-01" / filename
                payload = json.loads(path.read_text(encoding="utf-8"))
                payload["runId"] = "another-run"
                save(path, payload)
            card = report.render(root).split('class="nav-card" href="#normal-01"')[1].split('</a>')[0]
            self.assertNotIn('chip pass">PASS', card)

    def test_fault_pass_allows_user_quality_failure_but_requires_matching_saved_values(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "new-run"
            fault = {"initialOrderIds": ["o1"], "observationsComplete": True,
                     "loadMaintainedThroughRecovery": True, "detectionEvaluated": True,
                     "detectionPass": True, "recoveryPass": True}
            finished_run(root, "fault-01", fault=True, fault_data=fault)
            result_path = root / "fault-01" / "result.json"
            result = json.loads(result_path.read_text(encoding="utf-8"))
            result["verdict"] = "FAIL"
            result["goals"]["doneWithin5s"] = "FAIL"
            save(result_path, result)
            run_path = root / "fault-01" / "run.json"
            run = json.loads(run_path.read_text(encoding="utf-8"))
            run["quality"] = "FAIL"
            save(run_path, run)
            card = report.render(root).split('class="nav-card" href="#fault-01"')[1].split('</a>')[0]
            self.assertIn('chip pass">PASS', card)
            settlement_path = root / "fault-01" / "settlement.json"
            settlement = json.loads(settlement_path.read_text(encoding="utf-8"))
            settlement["verdict"] = "FAIL"
            save(settlement_path, settlement)
            card = report.render(root).split('class="nav-card" href="#fault-01"')[1].split('</a>')[0]
            self.assertNotIn('chip pass">PASS', card)
            settlement["verdict"] = "PASS"
            save(settlement_path, settlement)
            run["quality"] = "PASS"
            save(run_path, run)
            card = report.render(root).split('class="nav-card" href="#fault-01"')[1].split('</a>')[0]
            self.assertNotIn('chip pass">PASS', card)

    def test_fault_detection_failure_is_fail_even_if_recovery_observation_incomplete(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "quality-fixture"
            finished_run(root, "fault-01", verdict="FAIL", fault=True, fault_data={
                "killedAtMs": 1_000_000, "firstFiringMs": None, "detectionEvaluated": True,
                "detectionPass": False, "healthFailed": False, "observationsComplete": False,
                "initialOrderIds": ["o1"], "loadMaintainedThroughRecovery": False,
                "recoveryPass": True, "targetOrderIds": ["o1"]})
            page = report.render(root)
            section = page.split('<h3>탐지와 회복</h3>')[1].split('<h3>중간 과정')[0]
            self.assertIn('chip fail">FAIL', section)
            self.assertIn('chip unknown">INCONCLUSIVE', section)

    def test_fault_without_load_maintenance_cannot_be_official_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "quality-fixture"
            finished_run(root, "fault-01", fault=True, fault_data={
                "killedAtMs": 1_000_000, "firstFiringMs": 1_001_000,
                "detectionEvaluated": True, "detectionPass": True,
                "healthFailed": False, "observationsComplete": True,
                "initialOrderIds": ["o1"], "loadMaintainedThroughRecovery": False,
                "recoveryPass": True, "targetOrderIds": ["o1"]})
            page = report.render(root)
            self.assertIn("INCONCLUSIVE", page.split('class="nav-card" href="#fault-01"')[1].split('</a>')[0])
            section = page.split('<h3>탐지와 회복</h3>')[1].split('<h3>중간 과정')[0]
            recovery_row = section.split('서버 정상화 후 5분 내 추적 주문 완료</td>')[1].split('</tr>')[0]
            self.assertIn('chip unknown">INCONCLUSIVE', recovery_row)

    def test_reader_conclusion_precedes_audit_and_preserves_unknown_acceptance(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "quality-fixture"
            finished_run(root, "normal-01")
            finished_run(root, "normal-02")
            fault_data = {"killedAtMs": 1_000_000, "firstFiringMs": 1_001_000,
                          "detectionEvaluated": True, "detectionPass": True,
                          "healthFailed": False, "observationsComplete": True,
                          "initialOrderIds": ["o1"], "loadMaintainedThroughRecovery": True,
                          "recoveryPass": True, "targetOrderIds": ["o1"],
                          "recoveredOrders": {"o1": {}}}
            finished_run(root, "fault-02", fault=True, fault_data=fault_data)
            save(root / "fault-02" / "settlement.json", {
                "verdict": "INCONCLUSIVE", "counts": {"unknownAcceptance": 7}})
            save(root / "fault-02" / "run.json", {"verdict": "INCONCLUSIVE"})
            save(root / "clean-environment.json", {"project": "quality-fixture"})
            page = report.render(root)
            lead = page.index('class="run overview"')
            audit = page.index('실행별 종합 판정과 근거 적격성')
            self.assertLess(lead, audit)
            self.assertIn('주문 ID를 받지 못한 시도 7건', page[lead:audit])
            self.assertIn('자료가 부족해 통과·실패를 확정하지 못했다', page[lead:audit])
            self.assertIn('<details class="run audit"><summary>새 실행 환경', page)

    def test_missing_load_field_and_health_failure_never_promote_recovery(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "quality-fixture"
            data = {"killedAtMs": 1_000_000, "firstFiringMs": 1_001_000,
                    "detectionEvaluated": True, "detectionPass": True,
                    "healthFailed": False, "observationsComplete": True,
                    "initialOrderIds": ["o1"], "recoveryPass": True,
                    "targetOrderIds": ["o1"]}
            finished_run(root, "fault-01", fault=True, fault_data=data)
            self.assertEqual(report.official_verdict({"verdict": "PASS"}, "fault", data),
                             "INCONCLUSIVE")
            data["healthFailed"] = True
            self.assertEqual(report.official_verdict({"verdict": "PASS"}, "fault", data), "FAIL")


if __name__ == "__main__":
    unittest.main()
