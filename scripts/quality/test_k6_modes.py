"""Real k6 against a local HTTP contract; never uses the payment stack."""
import json
import shutil
import subprocess
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which('k6'), 'k6 required for HTTP contract')
class K6ModeTests(unittest.TestCase):
    def execute(self, mode=None, *, completion_delay=2, observe=False):
        calls = []
        created = {}

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def respond(self, status, data):
                payload = json.dumps({'data': data}).encode()
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                calls.append((self.path, body))
                if self.path.endswith('/checkout'):
                    order = self.headers['Idempotency-Key']
                    created[order] = time.monotonic()
                    self.respond(201, {'orderId': order, 'totalAmount': 1000})
                else:
                    self.respond(202, {'processingDelayed': False})

            def do_GET(self):
                calls.append((self.path, None))
                order = self.path.split('/')[-2]
                status = 'DONE' if time.monotonic()-created[order] >= completion_delay else 'IN_PROGRESS'
                self.respond(200, {'orderId': order, 'status': status})

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                events = Path(tmp)/'attempts.jsonl'
                summary = Path(tmp)/'summary.json'
                cmd = ['k6', 'run', '--quiet', '--log-format', 'raw', '--console-output', str(events)]
                env = {'RUN_ID': 'mode-test', 'RATE': '4', 'DURATION_SECONDS': '2',
                       'PREALLOCATED_VUS': '1', 'MAX_VUS': '1',
                       'BASE_URL': f'http://127.0.0.1:{server.server_port}',
                       'OUTPUT_SUMMARY': str(summary)}
                if mode is not None:
                    env['TEST_MODE'] = mode
                for key, value in env.items():
                    cmd.extend(['-e', f'{key}={value}'])
                cmd.append('scripts/k6/quality-payment.js')
                observer = feed = None
                if observe:
                    from observer import Observer
                    from run import AttemptFeed
                    observer = Observer(env['BASE_URL'], Path(tmp)/'observations.jsonl')
                    feed = AttemptFeed(events, observer)
                    feed.start()
                try:
                    process = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=20)
                finally:
                    if feed:
                        feed.close()
                        observer.close()
                observations = ([json.loads(line) for line in (Path(tmp)/'observations.jsonl').read_text().splitlines()]
                                if observe else [])
                records = [json.loads(line) for line in events.read_text().splitlines()] if events.exists() else []
                return process, records, calls, json.loads(summary.read_text()) if summary.exists() else {}, observations
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_new_modes_release_injection_slot_before_delayed_completion(self):
        for mode in ('capacity', 'user-quality'):
            with self.subTest(mode=mode):
                process, records, calls, summary, _ = self.execute(mode)
                self.assertEqual(process.returncode, 0, process.stderr)
                starts = [r for r in records if r['kind'] == 'start']
                finishes = [r for r in records if r['kind'] == 'finish']
                self.assertIn(len(starts), (8, 9))
                self.assertEqual(len(finishes), len(starts))
                self.assertFalse([path for path, _ in calls if path.endswith('/status')],
                                 'completion polling must run outside injection VUs')
                self.assertEqual(len(calls), len(starts)*2, 'no hidden extra purchases')
                self.assertEqual(summary['metrics']['dropped_iterations']['values']['count'], 0)
                for row in finishes:
                    self.assertEqual(row['mode'], mode)
                    self.assertEqual(row['outcome'], 'ACCEPTED')
                    self.assertIsNone(row['doneMs'])
                    self.assertEqual(row['pollCount'], 0)
                    self.assertLess(row['finishedMs']-row['confirmEndMs'], 500)
                    self.assertIsInstance(row['checkoutEndMs'], int)

    def test_default_and_explicit_legacy_keep_polling(self):
        for mode in (None, 'legacy'):
            with self.subTest(mode=mode):
                _, records, calls, _, _ = self.execute(mode, completion_delay=0)
                self.assertTrue(any(path.endswith('/status') for path, _ in calls))
                self.assertTrue(all(r['outcome'] == 'DONE' for r in records if r['kind']=='finish'))

    def test_separate_observer_uses_same_purchases_during_real_k6_injection(self):
        process, records, calls, summary, observations = self.execute('user-quality', observe=True)
        self.assertEqual(process.returncode, 0, process.stderr)
        injections = [r for r in records if r['kind'] == 'finish']
        finishes = [r for r in observations if r['kind'] == 'observation_finish']
        self.assertEqual(len(finishes), len(injections))
        self.assertEqual({r['orderId'] for r in finishes}, {r['orderId'] for r in injections})
        self.assertTrue(all(r['outcome'] == 'DONE' for r in finishes))
        self.assertEqual(sum(path.endswith('/checkout') for path, _ in calls), len(injections))
        self.assertTrue(any(path.endswith('/status') for path, _ in calls))
        self.assertEqual(summary['metrics']['dropped_iterations']['values']['count'], 0)
        self.assertTrue(all(r['pollCount'] == 0 for r in injections))

    def test_unknown_mode_is_rejected_before_requests(self):
        process, _, calls, _, _ = self.execute('typo')
        self.assertNotEqual(process.returncode, 0)
        self.assertEqual(calls, [])


if __name__ == '__main__':
    unittest.main()
