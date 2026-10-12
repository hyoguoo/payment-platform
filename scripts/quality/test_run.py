import subprocess
import unittest
from unittest.mock import patch
import run


class RunnerSafetyTests(unittest.TestCase):
    def test_payment_lookup_requires_exactly_one_running_instance(self):
        for output in ('', 'one\ntwo'):
            with patch('run.evidence.command', return_value=output):
                with self.assertRaises(RuntimeError):
                    run.payment_container()
        with patch('run.evidence.command', return_value='one'):
            self.assertEqual(run.payment_container(), 'one')

    def test_cleanup_restarts_stopped_payment_even_after_start_request(self):
        with patch('run.evidence.command', side_effect=['false', 'container']) as command:
            self.assertTrue(run.ensure_payment_running('container'))
            self.assertEqual(command.call_args.args[0], ['docker', 'start', 'container'])

    def test_cleanup_does_not_restart_an_already_running_payment(self):
        with patch('run.evidence.command', return_value='true') as command:
            self.assertFalse(run.ensure_payment_running('container'))
            self.assertEqual(command.call_count, 1)

    def test_late_health_keeps_full_recovery_window(self):
        self.assertTrue(run.fault_observation_pending(60000, 180000, 300000, False, 590000))
        self.assertFalse(run.fault_observation_pending(60000, 180000, 300000, True, 590000))

    def test_health_never_returns_has_bounded_wait(self):
        self.assertFalse(run.fault_observation_pending(60000, 180000, None, False, 480001))

    def test_failed_normal_goal_cannot_exit_success(self):
        self.assertEqual(run.combined_verdict('FAIL', 'PASS', None), ('FAIL', 1))

    def test_fault_user_errors_do_not_override_successful_recovery(self):
        fault = {'initialOrderIds': ['a'], 'detectionPass': True, 'recoveryPass': True, 'observationsComplete': True,
                 'loadMaintainedThroughRecovery': True}
        self.assertEqual(run.combined_verdict('FAIL', 'PASS', fault), ('PASS', 0))
        self.assertEqual(run.combined_verdict('FAIL', 'INCONCLUSIVE', fault), ('INCONCLUSIVE', 2))

    def test_empty_recovery_is_not_a_pass(self):
        fault = {'initialOrderIds': [], 'detectionPass': True, 'recoveryPass': False, 'observationsComplete': True}
        self.assertEqual(run.combined_verdict('FAIL', 'PASS', fault), ('INCONCLUSIVE', 2))

    def test_cohort_capture_failure_does_not_publish_health(self):
        initial = {'a'}
        with patch('run.evidence.get_json', return_value={'status': 'UP'}), patch('run.settlement.capture', side_effect=RuntimeError('DB unavailable')):
            with self.assertRaises(RuntimeError):
                run.observe_health_cohort(initial, 1)
        self.assertEqual(initial, {'a'})

    def test_confirmed_detection_failure_survives_empty_recovery(self):
        fault = {'initialOrderIds': [], 'detectionPass': False, 'detectionEvaluated': True,
                 'recoveryPass': False, 'observationsComplete': False}
        self.assertEqual(run.combined_verdict('FAIL', 'PASS', fault), ('FAIL', 1))

    def test_recovery_after_arrivals_end_is_not_proven_under_load(self):
        fault = {'initialOrderIds': ['a'], 'detectionPass': True, 'recoveryPass': True,
                 'observationsComplete': True, 'loadMaintainedThroughRecovery': False}
        self.assertEqual(run.combined_verdict('FAIL', 'PASS', fault), ('INCONCLUSIVE', 2))

    def test_missing_arrival_evidence_cannot_pass_recovery(self):
        fault = {'initialOrderIds': ['a'], 'detectionPass': True, 'recoveryPass': True,
                 'observationsComplete': True}
        self.assertEqual(run.combined_verdict('FAIL', 'PASS', fault), ('INCONCLUSIVE', 2))

    def test_late_recovery_beyond_actual_load_window(self):
        recovery = {'a': {'observedAtMs': 580000}}
        self.assertFalse(run.load_covers_recovery({'actualFirstStartMs': 0, 'actualLastStartMs': 550000,
                                                 'goals': {'arrival': 'PASS'}}, recovery, 60000))
        self.assertTrue(run.load_covers_recovery({'actualFirstStartMs': 0, 'actualLastStartMs': 600000,
                                                'goals': {'arrival': 'PASS'}}, recovery, 60000))

    def test_health_deadline_failure_is_not_missing_evidence(self):
        fault = {'initialOrderIds': ['a'], 'detectionPass': True, 'recoveryPass': False,
                 'observationsComplete': False, 'healthFailed': True}
        self.assertEqual(run.combined_verdict('FAIL', 'PASS', fault), ('FAIL', 1))


if __name__ == '__main__':
    unittest.main()
