import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

import run
from test_results import attempt, summary


class ModeIntegrationTests(unittest.TestCase):
    def test_default_mode_and_explicit_parameters(self):
        default = run.parse_args(['--run-id', 'test'])
        self.assertEqual(default.mode, 'legacy')
        self.assertEqual(default.phase, 'measurement')
        self.assertEqual(default.max_vus, 650)
        args = run.parse_args(['--run-id', 'test', '--mode', 'user-quality',
                               '--max-vus', '7', '--preallocated-vus', '2', '--observer-workers', '3', '--phase', 'warmup'])
        command = run.load_command(args, Path('/tmp/test'))
        self.assertIn('TEST_MODE=user-quality', command)
        self.assertIn('RUN_PHASE=warmup', command)
        self.assertIn('MAX_VUS=7', command)
        self.assertIn('PREALLOCATED_VUS=2', command)

    def test_invalid_modes_resources_and_unsupported_fault_fail_before_setup(self):
        for flags in (['--mode', 'typo'], ['--max-vus', '0'], ['--max-vus', '651'],
                      ['--max-vus', '3', '--preallocated-vus', '4'],
                      ['--observer-workers', '0'], ['--observer-workers', '257'],
                      ['--mode', 'capacity', '--fault', '--duration-seconds', '600']):
            with self.subTest(flags=flags), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                run.parse_args(['--run-id', 'test', *flags])

    def test_feed_waits_for_complete_lines_and_never_resubmits(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'attempts.jsonl'
            consumer = Mock()
            feed = run.AttemptFeed(path, consumer)
            row = dict(kind='finish', attemptId='same', orderId='same', confirmEndMs=1)
            encoded = json.dumps(row)
            path.write_text(encoded[:10])
            feed.pump()
            consumer.submit.assert_not_called()
            with path.open('a') as output:
                output.write(encoded[10:]+'\n'+json.dumps(dict(kind='start'))+'\n')
            feed.pump()
            feed.pump()
            consumer.submit.assert_called_once_with(row)
            feed.close()
            self.assertEqual(feed.errors, [])

    def test_truncated_and_malformed_feed_is_evidence_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'attempts.jsonl'
            path.write_text('broken\n{"kind":')
            feed = run.AttemptFeed(path, Mock())
            feed.close()
            self.assertEqual(len(feed.errors), 2)

    def test_unknown_acceptance_does_not_end_known_work_observation(self):
        from test_settlement import fixture
        _,_,after=fixture()
        report={'validityIssues':['unknown receipt']}
        verdict={'unknownAcceptance':[{'attemptId':'unknown'}],
                 'pending':['attempt report has validity issues'], 'failures':[]}
        after['paymentEvents'][0]['status']='IN_PROGRESS'
        self.assertTrue(run.settlement_needs_observation(report,verdict,after))
        after['paymentEvents'][0]['status']='DONE'
        self.assertFalse(run.settlement_needs_observation(report,verdict,after))

    def test_new_modes_cannot_inherit_legacy_completion_verdict(self):
        events = list(attempt(0))
        for mode in ('capacity', 'user-quality'):
            report = run.analyze_mode(events, summary(), 'run', 1, 1, mode)
            self.assertEqual(report['mode'], mode)
            self.assertEqual(report['schemaVersion'], 2)
            self.assertEqual(report['verdict'], 'INCONCLUSIVE')
            self.assertNotIn('doneWithin60s', report['quality']['goals'])
            self.assertNotIn('fastDone', report['counts'])
            self.assertEqual(report['attempts'][0]['orderId'], 'order-0')
        legacy = run.analyze_mode(events, summary(), 'run', 1, 1, 'legacy')
        self.assertEqual(legacy['verdict'], 'PASS')
        self.assertEqual(legacy['schemaVersion'], 1)


if __name__ == '__main__':
    unittest.main()
