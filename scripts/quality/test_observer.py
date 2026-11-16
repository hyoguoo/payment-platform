import json
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

from observer import Observer


class _StatusServer(ThreadingHTTPServer):
    def __init__(self, response, delay=0):
        self.response = response
        self.delay = delay
        self.requests = []
        self.client_ports = []
        super().__init__(('127.0.0.1', 0), _StatusHandler)


class _StatusHandler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def handle_one_request(self):
        try:
            super().handle_one_request()
        except ConnectionResetError:
            self.close_connection = True

    def do_GET(self):
        self.server.requests.append(self.path)
        self.server.client_ports.append(self.client_address[1])
        time.sleep(self.server.delay)
        status, body = self.server.response(self.path)
        if status is None:
            self.close_connection = True
            self.connection.close()
            return
        payload = json.dumps({'data': body}).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        try:
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_POST(self):
        self.server.requests.append(f'POST {self.path}')
        self.send_error(405)

    def log_message(self, *_args):
        pass


class ObserverTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'observations.jsonl'
        self.server = None

    def tearDown(self):
        if self.server:
            self.server.shutdown()
            self.server.server_close()
            self.thread.join()
        self.temp.cleanup()

    def serve(self, response, delay=0):
        self.server = _StatusServer(response, delay)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        return f'http://127.0.0.1:{self.server.server_port}'

    def record(self, suffix, accepted=202):
        return {'runId': 'r', 'attemptId': f'r-{suffix}', 'orderId': f'order-{suffix}',
                'confirmStartMs': int(time.time() * 1000),
                'confirmEndMs': int(time.time() * 1000), 'confirmStatus': accepted}

    def events(self):
        return [json.loads(line) for line in self.path.read_text().splitlines()]

    def test_slow_completion_does_not_hold_injection_and_polls_same_purchases_only(self):
        url = self.serve(lambda path: (200, {'orderId': path.split('/')[-2], 'status': 'DONE'}), delay=.08)
        observer = Observer(url, self.path, max_inflight=4, poll_interval_ms=20, max_polls=3)
        began = time.monotonic()
        for number in range(3):
            observer.submit(self.record(number))
        self.assertLess(time.monotonic() - began, .05)
        observer.close()
        self.assertEqual(observer.stats()['finished'], 3)
        self.assertEqual([e['outcome'] for e in self.events() if e['kind'] == 'observation_finish'],
                         ['TIMEOUT'] * 3)
        self.assertTrue(all(p.startswith('/api/v1/payments/order-') and p.endswith('/status')
                            for p in self.server.requests))

    def test_done_is_observed_with_original_order_and_confirm_uncertainty_is_retained(self):
        url = self.serve(lambda path: (200, {'orderId': path.split('/')[-2], 'status': 'DONE'}))
        observer = Observer(url, self.path, max_inflight=2, poll_interval_ms=20, max_polls=3)
        observer.submit(self.record(1, accepted=0))
        observer.close()
        final = next(e for e in self.events() if e['kind'] == 'observation_finish')
        self.assertEqual(final['outcome'], 'DONE')
        self.assertFalse(final['confirmAccepted'])
        self.assertEqual(final['orderId'], 'order-1')
        self.assertEqual(len(self.server.requests), 1)

    def test_poll_budget_and_cadence_are_recorded_without_hidden_extra_purchase(self):
        url = self.serve(lambda path: (200, {'orderId': path.split('/')[-2], 'status': 'IN_PROGRESS'}))
        self.assertEqual(Observer.POLL_INTERVAL_MS, 1000)
        self.assertEqual(Observer.MAX_POLLS, 30)
        self.assertEqual(Observer.REQUEST_TIMEOUT_SECONDS, 31)
        observer = Observer(url, self.path, max_inflight=2, poll_interval_ms=15, max_polls=4)
        record = self.record(2)
        observer.submit(record)
        observer.close()
        events = self.events()
        polls = [e for e in events if e['kind'] == 'poll_start']
        self.assertEqual([e['pollNo'] for e in polls], [1, 2, 3, 4])
        self.assertEqual(len(self.server.requests), 4)
        self.assertEqual([e['outcome'] for e in events if e['kind'] == 'observation_finish'], ['TIMEOUT'])
        # setInterval ticks use an absolute schedule: scheduler jitter may
        # shorten adjacent intervals, but no request may precede its own tick.
        for poll in polls:
            self.assertGreaterEqual(poll['atMs'], record['confirmEndMs'] + poll['pollNo'] * 15)

    def test_successive_polls_reuse_worker_http_connection(self):
        url = self.serve(lambda path: (200, {'orderId': path.split('/')[-2], 'status': 'IN_PROGRESS'}))
        observer = Observer(url, self.path, max_inflight=1, poll_interval_ms=40, max_polls=2)
        observer.submit(self.record(11))
        observer.close()
        self.assertEqual(len(self.server.client_ports), 2)
        self.assertEqual(len(set(self.server.client_ports)), 1)

    def test_repeated_broken_connections_do_not_accumulate_connection_objects(self):
        url = self.serve(lambda _path: (None, None))
        observer = Observer(url, self.path, max_inflight=1, poll_interval_ms=20, max_polls=3)
        observer.submit(self.record(12))
        observer.close()
        self.assertEqual(observer.stats()['errors'], 3)
        self.assertEqual(len(observer._connections), 0)

    def test_saturated_observer_marks_missing_polls_instead_of_silent_success(self):
        url = self.serve(lambda path: (200, {'orderId': path.split('/')[-2], 'status': 'DONE'}), delay=.1)
        observer = Observer(url, self.path, max_inflight=1, poll_interval_ms=20, max_polls=2)
        observer.submit(self.record(3))
        observer.submit(self.record(4))
        observer.close()
        self.assertGreater(observer.stats()['capacitySkipped'], 0)
        self.assertTrue(any(e['kind'] == 'poll_skip' and e['reason'] == 'capacity'
                            for e in self.events()))

    def test_missing_order_and_full_queue_are_explicitly_incomplete(self):
        url = self.serve(lambda path: (200, {'orderId': path.split('/')[-2], 'status': 'IN_PROGRESS'}))
        observer = Observer(url, self.path, max_inflight=1, max_active=1,
                            poll_interval_ms=20, max_polls=2)
        missing = self.record(7)
        missing['orderId'] = None
        observer.submit(missing)
        observer.submit(self.record(8))
        observer.submit(self.record(9))
        observer.close()
        self.assertEqual(observer.stats()['unobservable'], 1)
        self.assertEqual(observer.stats()['rejected'], 1)
        self.assertTrue(observer.stats()['incomplete'])
        self.assertEqual(observer.stats()['submitted'], observer.stats()['finished'])
        self.assertTrue(any(e['reason'] == 'observer_queue_full' for e in self.events()
                            if e['kind'] == 'observation_finish'))

    def test_errors_and_late_done_do_not_become_on_time_completion(self):
        url = self.serve(lambda path: (503, {'orderId': path.split('/')[-2]}))
        observer = Observer(url, self.path, max_inflight=1, poll_interval_ms=20, max_polls=1)
        observer.submit(self.record(5))
        observer.close()
        events = self.events()
        self.assertEqual([e['outcome'] for e in events if e['kind'] == 'observation_finish'], ['TIMEOUT'])
        self.assertTrue(any(e['kind'] == 'poll_result' and e['httpStatus'] == 503 for e in events))

    def test_done_response_arriving_after_deadline_stays_timeout(self):
        url = self.serve(lambda path: (200, {'orderId': path.split('/')[-2], 'status': 'DONE'}))
        observer = Observer(url, self.path, max_inflight=1, poll_interval_ms=20, max_polls=1)

        def late_done(order_id, _timeout):
            time.sleep(.05)
            return 200, {'orderId': order_id, 'status': 'DONE'}

        with patch.object(observer, '_poll', side_effect=late_done):
            observer.submit(self.record(6))
            observer.close()
        events = self.events()
        self.assertEqual([e['outcome'] for e in events if e['kind'] == 'observation_finish'], ['TIMEOUT'])
        self.assertTrue(any(e['kind'] == 'poll_result' and e['late'] and e['observedStatus'] == 'DONE'
                            for e in events))

    def test_on_time_response_is_not_lost_when_callback_waits_past_deadline(self):
        url = self.serve(lambda path: (200, {'orderId': path.split('/')[-2], 'status': 'DONE'}))
        observer = Observer(url, self.path, max_inflight=1, poll_interval_ms=50, max_polls=1)
        completed_http = threading.Event()
        release_callback = threading.Event()
        original_complete = observer._complete

        def delayed_callback(*args):
            completed_http.set()
            release_callback.wait(timeout=1)
            original_complete(*args)

        def quick_done(order_id, _timeout):
            time.sleep(.005)
            return 200, {'orderId': order_id, 'status': 'DONE'}

        with patch.object(observer, '_poll', side_effect=quick_done), \
                patch.object(observer, '_complete', side_effect=delayed_callback):
            observer.submit(self.record(10))
            self.assertTrue(completed_http.wait(timeout=.3))
            time.sleep(.1)
            release_callback.set()
            observer.close()
        events = self.events()
        self.assertEqual([e['outcome'] for e in events if e['kind'] == 'observation_finish'], ['DONE'])


if __name__ == '__main__':
    unittest.main()
