"""Independent, bounded status observer for the user-quality load mode.

The k6 process only injects purchases. This process reads its finish records and
observes the same orders, so slow status responses never consume k6 arrival VUs.
"""
import heapq
import http.client
import json
import threading
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


class Observer:
    POLL_INTERVAL_MS = 1000
    MAX_POLLS = 30
    REQUEST_TIMEOUT_SECONDS = 31

    def __init__(self, base_url, output_path, max_inflight=32,
                 request_timeout_seconds=REQUEST_TIMEOUT_SECONDS,
                 poll_interval_ms=POLL_INTERVAL_MS, max_polls=MAX_POLLS, max_active=100_000):
        if (max_inflight < 1 or request_timeout_seconds <= 0 or poll_interval_ms < 1
                or max_polls < 1 or max_active < 1):
            raise ValueError('observer limits must be positive')
        self.base_url = base_url.rstrip('/')
        self._endpoint = urllib.parse.urlsplit(self.base_url)
        if self._endpoint.scheme not in ('http', 'https') or not self._endpoint.hostname:
            raise ValueError('base_url must be an HTTP(S) URL')
        self.interval = poll_interval_ms
        self.max_polls = max_polls
        self.max_inflight = max_inflight
        self.max_active = max_active
        self.request_timeout = request_timeout_seconds
        self._condition = threading.Condition(threading.RLock())
        self._slots = threading.BoundedSemaphore(max_inflight)
        self._pool = ThreadPoolExecutor(max_workers=max_inflight, thread_name_prefix='quality-poll')
        self._worker_local = threading.local()
        self._connections = set()
        self._output = Path(output_path).open('w')
        self._orders = {}
        self._schedule = []
        self._closed = False
        self._counts = {'submitted': 0, 'finished': 0, 'polls': 0,
                        'capacitySkipped': 0, 'lateSkipped': 0, 'errors': 0,
                        'activePeak': 0, 'rejected': 0, 'unobservable': 0,
                        'maxScheduleLatenessMs': 0}
        self._thread = threading.Thread(target=self._run, daemon=True, name='quality-observer')
        self._thread.start()

    @staticmethod
    def _now():
        return int(time.time() * 1000)

    def _write(self, event):
        self._output.write(json.dumps(event, ensure_ascii=False) + '\n')
        self._output.flush()

    def submit(self, record):
        """Nonblocking admission of one injected purchase; never sends a purchase."""
        with self._condition:
            if self._closed:
                raise RuntimeError('observer is closed')
            self._counts['submitted'] += 1
            attempt_id = record['attemptId']
            order_id = record.get('orderId')
            confirm_end = record.get('confirmEndMs')
            common = {'runId': record.get('runId'), 'attemptId': attempt_id,
                      'orderId': order_id, 'confirmStartMs': record.get('confirmStartMs'),
                      'confirmEndMs': confirm_end, 'confirmStatus': record.get('confirmStatus'),
                      'confirmAccepted': record.get('confirmStatus') == 202}
            if not isinstance(order_id, str) or not order_id or not isinstance(confirm_end, int):
                self._counts['unobservable'] += 1
                self._finish(common, 'UNOBSERVABLE', 'missing_order_or_confirm_end', 0)
                return
            if attempt_id in self._orders:
                self._counts['rejected'] += 1
                self._finish(common, 'UNOBSERVABLE', 'duplicate_attempt_id', 0)
                return
            if len(self._orders) >= self.max_active:
                self._counts['rejected'] += 1
                self._finish(common, 'UNOBSERVABLE', 'observer_queue_full', 0)
                return
            common['deadlineMs'] = confirm_end + (self.max_polls + 1) * self.interval
            common['pollCount'] = 0
            common['capacitySkipped'] = 0
            common['pendingPolls'] = 0
            common['deadlinePassed'] = False
            self._orders[attempt_id] = common
            self._counts['activePeak'] = max(self._counts['activePeak'], len(self._orders))
            heapq.heappush(self._schedule, (confirm_end + self.interval, attempt_id, 1))
            self._condition.notify()

    def _finish(self, state, outcome, reason, at_ms):
        attempt_id = state['attemptId']
        if self._orders.get(attempt_id) is state:
            self._orders.pop(attempt_id)
        self._counts['finished'] += 1
        self._write({k: v for k, v in state.items() if k != 'pollCount'} |
                    {'kind': 'observation_finish', 'atMs': at_ms or self._now(),
                     'outcome': outcome, 'reason': reason, 'pollCount': state.get('pollCount', 0)})
        self._condition.notify_all()

    def _run(self):
        with self._condition:
            while True:
                if self._closed and not self._orders:
                    return
                if not self._schedule:
                    self._condition.wait(timeout=.1)
                    continue
                due, attempt_id, poll_no = self._schedule[0]
                remaining = (due - self._now()) / 1000
                if remaining > 0:
                    self._condition.wait(timeout=min(remaining, .1))
                    continue
                heapq.heappop(self._schedule)
                state = self._orders.get(attempt_id)
                if state is None:
                    continue
                now = self._now()
                if poll_no > self.max_polls:
                    state['deadlinePassed'] = True
                    if state['pendingPolls'] == 0:
                        self._finish(state, 'TIMEOUT', 'poll_deadline', now)
                    continue
                self._counts['maxScheduleLatenessMs'] = max(
                    self._counts['maxScheduleLatenessMs'], now - due)
                heapq.heappush(self._schedule,
                               (state['confirmEndMs'] + (poll_no + 1) * self.interval,
                                attempt_id, poll_no + 1))
                if now >= state['deadlineMs'] or now >= due + self.interval:
                    self._counts['lateSkipped'] += 1
                    self._write({'kind': 'poll_skip', 'attemptId': attempt_id,
                                 'orderId': state['orderId'], 'pollNo': poll_no,
                                 'atMs': now,
                                 'reason': 'deadline' if now >= state['deadlineMs'] else 'schedule_late'})
                    continue
                if not self._slots.acquire(blocking=False):
                    self._counts['capacitySkipped'] += 1
                    state['capacitySkipped'] += 1
                    self._write({'kind': 'poll_skip', 'attemptId': attempt_id,
                                 'orderId': state['orderId'], 'pollNo': poll_no,
                                 'atMs': now, 'reason': 'capacity'})
                    continue
                state['pollCount'] += 1
                state['pendingPolls'] += 1
                self._counts['polls'] += 1
                self._write({'kind': 'poll_start', 'attemptId': attempt_id,
                             'orderId': state['orderId'], 'pollNo': poll_no, 'atMs': now})
                timeout = min(self.request_timeout, max(.001, (state['deadlineMs'] - now) / 1000))
                future = self._pool.submit(self._poll, state['orderId'], timeout)
                future.add_done_callback(lambda f, aid=attempt_id, n=poll_no, start=now,
                                         deadline=state['deadlineMs'], order=state['orderId']:
                                         self._complete(f, aid, n, start, deadline, order, self._now()))

    def _poll(self, order_id, timeout):
        path = (self._endpoint.path.rstrip('/') + '/api/v1/payments/' +
                urllib.parse.quote(order_id, safe='') + '/status')
        connection = getattr(self._worker_local, 'connection', None)
        if connection is None:
            kind = (http.client.HTTPSConnection if self._endpoint.scheme == 'https'
                    else http.client.HTTPConnection)
            connection = kind(self._endpoint.hostname, self._endpoint.port, timeout=timeout)
            self._worker_local.connection = connection
            with self._condition:
                self._connections.add(connection)
        try:
            connection.timeout = timeout
            if connection.sock is not None:
                connection.sock.settimeout(timeout)
            connection.request('GET', path, headers={'Accept': 'application/json'})
            response = connection.getresponse()
            status, payload = response.status, response.read()
        except Exception:
            connection.close()
            self._worker_local.connection = None
            with self._condition:
                self._connections.discard(connection)
            raise
        data = None
        if status == 200:
            body = json.loads(payload)
            data = body.get('data', body) if isinstance(body, dict) else None
        return status, data

    def _complete(self, future, attempt_id, poll_no, start_ms, deadline_ms, order_id,
                  observed_at):
        try:
            status, data = future.result()
            error = None
        except Exception as exc:
            status, data = None, None
            error = f'{type(exc).__name__}:{str(exc)[:160]}'
        finally:
            self._slots.release()
        with self._condition:
            state = self._orders.get(attempt_id)
            late = observed_at > deadline_ms
            observed_status = data.get('status') if isinstance(data, dict) else None
            observed_order = data.get('orderId') if isinstance(data, dict) else None
            if status != 200 and error is None:
                error = f'http_{status}'
            elif status == 200 and (not observed_order or
                                    observed_order != order_id):
                error = 'status_order_missing_or_mismatch'
            if error:
                self._counts['errors'] += 1
            self._write({'kind': 'poll_result', 'attemptId': attempt_id,
                         'orderId': order_id,
                         'pollNo': poll_no, 'requestStartMs': start_ms,
                         'atMs': observed_at, 'httpStatus': status,
                         'observedStatus': observed_status, 'late': late, 'error': error})
            if state:
                state['pendingPolls'] -= 1
                if not late and not error and observed_status in ('DONE', 'FAILED', 'QUARANTINED'):
                    self._finish(state, observed_status, 'terminal_status', observed_at)
                elif state['deadlinePassed'] and state['pendingPolls'] == 0:
                    self._finish(state, 'TIMEOUT', 'poll_deadline', self._now())

    def close(self):
        with self._condition:
            self._closed = True
            self._condition.notify_all()
        self._thread.join()
        self._pool.shutdown(wait=True)
        with self._condition:
            for connection in self._connections:
                connection.close()
            self._output.close()

    def stats(self):
        with self._condition:
            return dict(self._counts, active=len(self._orders),
                        maxInflight=self.max_inflight, maxActive=self.max_active,
                        pollIntervalMs=self.interval, maxPolls=self.max_polls,
                        requestTimeoutSeconds=self.request_timeout,
                        deadlineOffsetMs=(self.max_polls + 1) * self.interval,
                        incomplete=bool(self._counts['rejected'] or self._counts['unobservable']
                                        or self._counts['capacitySkipped']
                                        or self._counts['lateSkipped']))
