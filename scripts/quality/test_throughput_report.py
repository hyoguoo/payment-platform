"""Separated throughput modes retain their own evidence and verdicts."""
import json
import tempfile
import unittest
from pathlib import Path

from scripts.quality import report
from scripts.quality.test_report import finished_run, save


def new_run(root, name, mode):
    folder = root / name
    save(folder / 'result.json', {
        'schemaVersion': 2, 'runId': name, 'mode': mode, 'verdict': 'INCONCLUSIVE',
        'rate': 20, 'durationSeconds': 180,
        'counts': {'expected': 3600, 'started': 3600, 'dropped': 0},
        'validityIssues': [], 'evidenceIssues': ['T2.2 pending'],
        'goals': {'arrival': 'PASS'},
    })
    save(folder / 'run.json', {'runId': name, 'mode': mode, 'verdict': 'INCONCLUSIVE',
                              'quality': 'INCONCLUSIVE', 'settlement': 'PASS',
                              'sourceChanged': False, 'exitCode': 2})
    save(folder / 'settlement.json', {'runId': name, 'schemaVersion': 1, 'verdict': 'PASS',
                                     'counts': {'attempts': 3600, 'knownOrders': 3600,
                                                'unknownAcceptance': 0, 'unattributedNewOrders': 0,
                                                'pending': 0, 'failures': 0},
                                     'unknownAcceptance': [], 'unattributedNewOrders': [],
                                     'pending': [], 'failures': []})
    save(folder / 'command.json', {
        'mode': mode, 'phase':'measurement', 'rate': 20, 'durationSeconds': 180,
        'preallocatedVUs': 50, 'maxVUs': 100, 'observerWorkers': 32,
        'composeProject': 'fresh-project', 'outputRoot': 'live-drill/fresh-project'})
    (folder / 'attempts.jsonl').write_text('{}\n')
    (folder / 'observations.jsonl').write_text('{}\n')
    (folder / 'observer.json').write_text('{}\n')
    (folder / 'capacity-samples.jsonl').write_text('{}\n')
    return folder


def measured_run(root, mode='user-quality', done_latency=1200):
    from scripts.quality.test_throughput import fixture
    # analyze uses the same raw contracts as run.py.
    import sys
    sys.path.insert(0, str(Path(__file__).parent))
    from throughput import analyze
    args = fixture(done_latency=done_latency,workload=mode)
    result = analyze(mode=mode, **args)
    folder = root/'r'
    settled = dict(args['settled'], schemaVersion=1, runId='r',
                   counts={'attempts':120,'knownOrders':120,'unknownAcceptance':0,
                           'pending':0,'failures':0,'unattributedNewOrders':0},unattributedNewOrders=[])
    save(folder/'result.json',result)
    save(folder/'settlement.json',settled)
    save(folder/'run.json',dict(runId='r',mode=mode,verdict=result['verdict'],
        quality=result['verdict'],settlement='PASS',sourceChanged=False,phase='measurement',
        exitCode={'PASS':0,'FAIL':1,'INCONCLUSIVE':2}[result['verdict']]))
    save(folder/'command.json',dict(runId='r',mode=mode,phase='measurement',rate=1,durationSeconds=120,
         preallocatedVUs=100,maxVUs=650,observerWorkers=32,composeProject='mock-only',outputRoot='live-drill/mock-only'))
    for name,key in (('before.json','before'),('after.json','after'),('summary.json','summary'),
                     ('observer.json','observer_stats')):
        save(folder/name,args[key])
    for name,key in (('attempts.jsonl','events'),('observations.jsonl','observations'),
                     ('capacity-samples.jsonl','trend_samples')):
        (folder/name).write_text(''.join(json.dumps(row)+'\n' for row in args[key]))
    return folder


class ThroughputReportTests(unittest.TestCase):
    def test_actual_analyzer_pass_and_delayed_sustained_failure_are_preserved(self):
        for delay, verdict in ((1200,'PASS'),(6000,'FAIL')):
            with self.subTest(delay=delay), tempfile.TemporaryDirectory() as temp:
                root=Path(temp)
                measured_run(root,done_latency=delay)
                page=report.render(root)
                self.assertIn('class="chip '+('pass' if verdict=='PASS' else 'fail')+'">'+verdict,page)
                self.assertIn('QUALITY_MET' if delay==1200 else 'DELAYED_SUSTAINED',page)

    def test_saved_pass_cannot_override_missing_or_out_of_target_numbers(self):
        for field,value in (('checkoutP95Ms',501),('completionP95Ms',3001),
                            ('completionP99Ms',None),('completionP99Ms',float('nan'))):
            with self.subTest(field=field,value=value), tempfile.TemporaryDirectory() as temp:
                root=Path(temp)
                folder=measured_run(root)
                data=json.loads((folder/'result.json').read_text())
                data['quality'][field]=value
                save(folder/'result.json',data)
                self.assertNotIn('class="chip pass">PASS',report.render(root))

    def test_warmup_is_explicitly_excluded_from_capacity_limit_selection(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            folder=measured_run(root)
            data=json.loads((folder/'result.json').read_text())
            data['phase']='warmup'
            save(folder/'result.json',data)
            self.assertGreaterEqual(report.render(root).count('워밍업 · 한계 산정 제외'),2)

    def test_fail_summaries_without_raw_evidence_or_mismatched_phase_stay_unconfirmed(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            folder=measured_run(root,done_latency=6000)
            (folder/'attempts.jsonl').unlink()
            self.assertNotIn('class="chip fail">FAIL',report.render(root))
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            folder=measured_run(root)
            command=json.loads((folder/'command.json').read_text())
            command['phase']='warmup'
            save(folder/'command.json',command)
            self.assertNotIn('class="chip pass">PASS',report.render(root))

    def test_mixed_report_preserves_excluded_root(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            measured_run(root)
            finished_run(root,'normal-01')
            save(root/'evidence-status.json', {'status':'EXCLUDED','reason':'preserved original exclusion'})
            page=report.render(root)
            self.assertNotIn('class="chip pass">PASS',page)
            self.assertIn('preserved original exclusion',page)

    def test_new_modes_are_discovered_with_mode_specific_not_legacy_targets(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            new_run(root, 'capacity-20', 'capacity')
            new_run(root, 'quality-20', 'user-quality')
            page = report.render(root)
            self.assertIn('capacity-20', page)
            self.assertIn('quality-20', page)
            self.assertIn('서버 처리 용량', page)
            self.assertIn('사용자 품질', page)
            self.assertNotIn('전체 시도의 ≥99.9%가 60초 내 DONE', page)
            self.assertIn('INCONCLUSIVE', page)
            self.assertIn('observations.jsonl', page)
            self.assertIn('observer.json', page)
            self.assertIn('capacity-samples.jsonl', page)
            self.assertNotIn('0 ms', page)

    def test_legacy_run_in_same_root_keeps_original_verdict_and_policy(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            new_run(root, 'capacity-20', 'capacity')
            finished_run(root, 'normal-01')
            page = report.render(root)
            self.assertIn('normal-01', page)
            self.assertIn('500ms', page)
            self.assertIn('기존 조건', page)
            self.assertIn('capacity-20', page)
            self.assertNotIn('전체 시도의 ≥99.9%가 60초 내 DONE', page)

    def test_new_command_keeps_mode_and_resource_settings(self):
        command = {'mode': 'user-quality', 'phase':'measurement', 'rate': 10, 'durationSeconds': 180,
                   'preallocatedVUs': 30, 'maxVUs': 200, 'observerWorkers': 32,
                   'composeProject': 'fresh', 'outputRoot': 'live-drill/fresh'}
        line = report.safe_command(command, 'quality-10')
        self.assertIn('--mode user-quality', line)
        self.assertIn('--max-vus 200', line)
        self.assertIn('--observer-workers 32', line)
        self.assertIn('--preallocated-vus 30', line)
        self.assertIn('COMPOSE_PROJECT_NAME=fresh', line)
        command.pop('outputRoot')
        self.assertIn('기록 부족', report.safe_command(command, 'quality-10'))

    def test_new_report_never_promotes_verdict_only_or_mismatched_run(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            folder = new_run(root, 'capacity-20', 'capacity')
            result = json.loads((folder / 'result.json').read_text())
            run = json.loads((folder / 'run.json').read_text())
            result['verdict'] = run['quality'] = run['verdict'] = 'PASS'
            run['exitCode'] = 0
            save(folder / 'result.json', result)
            save(folder / 'run.json', run)
            page = report.render(root)
            self.assertNotIn('class="chip pass">PASS', page)
            result['evidenceIssues'] = []
            result['runId'] = 'another-run'
            save(folder / 'result.json', result)
            page = report.render(root)
            self.assertNotIn('class="chip pass">PASS', page)


if __name__ == '__main__':
    unittest.main()
