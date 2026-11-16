import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import capacity


CONTAINERS = [f'c{i}' for i in range(20)]

def stats():
    return '\n'.join(json.dumps({'Name':name, 'MemUsage':'10MiB / 1GiB'}) for name in CONTAINERS)

JOBS = ('payment-service', 'pg-service', 'product-service', 'user-service', 'gateway')


def prometheus(value=100):
    return {'status': 'success', 'data': {'result': [
        {'metric': {'job': job}, 'value': [capacity.now_ms()/1000, str(value)]} for job in JOBS]}}


class CapacityCaptureTests(unittest.TestCase):
    def setUp(self):
        self.command = patch('capacity.evidence.command', side_effect=lambda cmd, **kw:
            stats() if cmd[:2] == ['docker','stats'] else 'total = 0.00M used = 0.00M free = 0.00M').start()
        self.addCleanup(patch.stopall)

    def test_fixed_numeric_watermark_excludes_warmup_and_captures_committed_counts(self):
        before = {'paymentEvents': [{'id': 10}, {'id': 25}]}
        with patch('capacity.settlement._mysql', side_effect=['7\t4\t2\t123.5\n','5\n']) as query, \
                patch('capacity.evidence.get_json', side_effect=[prometheus(), prometheus(1),prometheus(capacity.now_ms()/1000)]):
            row = capacity.capture(capacity.watermark(before), stock_baseline=1, container_ids=CONTAINERS)
        self.assertIn('WHERE id > 25', query.call_args_list[0].args[1])
        self.assertEqual((row['orders'], row['done'], row['pending']), (7, 4, 2))
        self.assertEqual(row['oldestPendingAgeMs'], 123.5)
        self.assertEqual(set(row['memoryBytes']) & set(JOBS), set(JOBS))
        self.assertEqual(row['stockCommitted'],4)
        self.assertEqual(row['stockPending'],0)
        self.assertEqual(row['pipelinePending'],3)
        self.assertEqual(row['memoryBytes']['container:c0'],10*1024**2)
        self.assertEqual(row['memoryBytes']['host:swapUsedBytes'],0)
        self.assertEqual(row['memoryKind'], 'JVM heap + Docker stats + host swap')
        self.assertGreaterEqual(row['captureEndMs'], row['captureStartMs'])

    def test_stale_scrapes_are_not_fresh_stable_memory(self):
        with patch('capacity.settlement._mysql',side_effect=['0\t0\t0\t0','0']), \
                patch('capacity.evidence.get_json',side_effect=[prometheus(),prometheus(1),prometheus(capacity.now_ms()/1000-60)]):
            with self.assertRaises(ValueError):
                capacity.capture(0,stock_baseline=0,container_ids=CONTAINERS)

    def test_missing_or_nonfinite_memory_is_not_stable_evidence(self):
        for bad in ({'status': 'error'}, prometheus(float('nan')), prometheus(-1),
                    {'status': 'success', 'data': {'result': []}}):
            with self.subTest(bad=bad), patch('capacity.settlement._mysql', side_effect=['0\t0\t0\t0','0']), \
                    patch('capacity.evidence.get_json', return_value=bad):
                with self.assertRaises(ValueError):
                    capacity.capture(0,stock_baseline=0,container_ids=CONTAINERS)

    def test_down_service_memory_is_not_healthy_sample(self):
        with patch('capacity.settlement._mysql', side_effect=['0\t0\t0\t0','0']), \
                patch('capacity.evidence.get_json', side_effect=[prometheus(), prometheus(0)]):
            with self.assertRaises(ValueError):
                capacity.capture(0,stock_baseline=0,container_ids=CONTAINERS)

    def test_invalid_counts_and_watermark_are_rejected(self):
        for value in (-1, '1 OR 1=1', 1.5, True):
            with self.subTest(value=value), self.assertRaises(ValueError):
                capacity.capture(value)
        with patch('capacity.settlement._mysql', return_value='1\t4\t2\t0'), \
                self.assertRaises(ValueError):
            capacity.capture(0,stock_baseline=0,container_ids=CONTAINERS)

    def test_sampler_keeps_capture_failure_and_final_sample_in_raw_evidence(self):
        import json
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'samples.jsonl'
            sampler = capacity.CapacitySampler(path, 0, interval_seconds=10)
            with patch('capacity.capture', side_effect=[RuntimeError('DB unavailable'), {'atMs': 123}]), \
                    patch.object(sampler, '_run'):
                sampler.sample()
                sampler.close()
                sampler.close()  # cleanup paths must not append or capture twice
            rows = [json.loads(line) for line in path.read_text().splitlines()]
            self.assertEqual(len(rows), 2)
            self.assertIn('captureError', rows[0])
            self.assertEqual(rows[1]['atMs'], 123)


if __name__ == '__main__':
    unittest.main()
