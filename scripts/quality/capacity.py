"""Read committed payment counters and JVM memory, without per-order HTTP polls."""
import json
import math
import re
import threading
import time
import urllib.parse
from pathlib import Path

import evidence
import settlement

JOBS = {'payment-service', 'pg-service', 'product-service', 'user-service', 'gateway'}


def now_ms():
    return int(time.time() * 1000)


def watermark(before):
    return max((row['id'] for row in before['paymentEvents']), default=0)


def _instant(query, evaluation_ms=None):
    url = 'http://localhost:9090/api/v1/query?' + urllib.parse.urlencode({'query': query, 'time': (evaluation_ms or now_ms())/1000})
    result = evidence.get_json(url, timeout=3)
    if result.get('status') != 'success':
        raise ValueError('missing Prometheus instant query result')
    values = {}
    for series in result.get('data', {}).get('result', []):
        job = series.get('metric', {}).get('job')
        if job in values:
            raise ValueError('duplicate memory/health series for job')
        value = float(series['value'][1])
        if not math.isfinite(value) or value < 0:
            raise ValueError('nonfinite or negative memory/health value')
        values[job] = value
    if set(values) != JOBS:
        raise ValueError('missing business service memory/health series')
    return values


def memory_usage_bytes(text):
    value = text.split('/')[0].strip()
    match = re.fullmatch(r'([0-9.]+)\s*(B|kB|KB|MB|GB|TB|KiB|MiB|GiB|TiB|K|M|G)', value)
    if not match:
        raise ValueError('unsupported memory unit')
    factors={'B':1,'kB':1000,'KB':1000,'MB':1000**2,'GB':1000**3,'TB':1000**4,
             'KiB':1024,'MiB':1024**2,'GiB':1024**3,'TiB':1024**4,'K':1024,'M':1024**2,'G':1024**3}
    value=float(match[1])*factors[match[2]]
    if not math.isfinite(value) or value < 0:
        raise ValueError('invalid memory usage')
    return int(value)


def runtime_memory(container_ids):
    if container_ids is None or len(container_ids) != 20 or len(set(container_ids)) != 20:
        raise ValueError('all 20 dedicated quality containers required for memory evidence')
    raw=evidence.command(['docker','stats','--no-stream','--format','{{json .}}',*container_ids],timeout=20)
    rows=[json.loads(line) for line in raw.splitlines()]
    values={'container:'+row['Name']:memory_usage_bytes(row['MemUsage']) for row in rows}
    if len(rows) != 20 or len(values) != 20:
        raise ValueError('incomplete container memory sample')
    swap=evidence.command(['sysctl','-n','vm.swapusage'])
    used=re.search(r'used\s*=\s*([0-9.]+[KMG])',swap)
    if not used:
        raise ValueError('host swap usage unavailable')
    values['host:swapUsedBytes']=memory_usage_bytes(used[1])
    return values


def capture(after_id, *, stock_baseline=0, container_ids=None):
    if type(stock_baseline) is not int or stock_baseline < 0:
        raise ValueError('nonnegative stock baseline required')
    if type(after_id) is not int or after_id < 0:
        raise ValueError('watermark must be a nonnegative integer')
    start = now_ms()
    # One read of current committed rows; the run has a dedicated environment.
    # Unknown/unattributed new orders remain visible and are reconciled later.
    sql = ("SELECT COUNT(*), COALESCE(SUM(status = 'DONE'), 0), "
           "COALESCE(SUM(status NOT IN ('DONE','FAILED','EXPIRED')), 0), "
           "COALESCE(MAX(CASE WHEN status NOT IN ('DONE','FAILED','EXPIRED') "
           "THEN TIMESTAMPDIFF(MICROSECOND, created_at, UTC_TIMESTAMP(6))/1000 END), 0) "
           f"FROM payment_event WHERE id > {after_id};")
    columns = settlement._mysql('payment', sql).strip().split('\t')
    if len(columns) != 4:
        raise ValueError('expected four capacity counters')
    orders, done, pending = map(int, columns[:3])
    age = float(columns[3])
    if not 0 <= done <= orders or not 0 <= pending <= orders-done or not math.isfinite(age) or age < 0:
        raise ValueError('invalid committed capacity counters')
    observed = now_ms()
    stock_total = int(settlement._mysql('product','SELECT COUNT(*) FROM stock_commit_dedupe;').strip())
    stock_observed=now_ms()
    stock_committed=stock_total-stock_baseline
    if stock_committed < 0:
        raise ValueError('stock completion counter decreased; dedupe cleanup invalidates cohort count')
    jobs = '|'.join(sorted(JOBS))
    memory = _instant('sum by(job) (jvm_memory_used_bytes{area="heap",job=~"'+jobs+'"})',start)
    health = _instant('up{job=~"'+jobs+'"}',start)
    if any(value != 1 for value in health.values()):
        raise ValueError('business service is down; memory evidence incomplete')
    source_at = _instant('min by(job) (timestamp(jvm_memory_used_bytes{area="heap",job=~"'+jobs+'"}) or timestamp(up{job=~"'+jobs+'"}))',start)
    source_at_ms={job:int(value*1000) for job,value in source_at.items()}
    if any(not -1000 <= start-stamp <= 30000 for stamp in source_at_ms.values()):
        raise ValueError('stale or future original Prometheus scrape sample')
    memory.update(runtime_memory(container_ids))
    return {'atMs': observed, 'captureStartMs': start, 'captureEndMs': now_ms(),
            'watermarkId': after_id, 'orders': orders, 'done': done, 'pending': pending,
            'oldestPendingAgeMs': age, 'memoryBytes': memory, 'memoryKind': 'JVM heap + Docker stats + host swap',
            'stockCommitted':stock_committed,'stockPending':max(0,done-stock_committed),
            'pipelinePending':max(0,orders-stock_committed),'stockObservedAtMs':stock_observed,
            'stockBaselineCount':stock_baseline,'sourceSampleAtMs':source_at_ms,
            'sourceFreshnessLimitMs':30000,'memoryCoverage':{'heapJobs':sorted(JOBS),'containerCount':20,'hostSwap':True},
            'counterMeaning': 'current committed payment_event rows after pre-load ID watermark'}


class CapacitySampler:
    def __init__(self, path, after_id, interval_seconds=10, *, stock_baseline=0, container_ids=None):
        if interval_seconds <= 0:
            raise ValueError('positive sampling interval required')
        self.path, self.after_id = Path(path), after_id
        self.interval_seconds = interval_seconds
        self.stock_baseline, self.container_ids = stock_baseline, container_ids
        self._stop = threading.Event()
        self._thread = None
        self._closed = False

    def sample(self):
        started = now_ms()
        try:
            row = capture(self.after_id,stock_baseline=self.stock_baseline,container_ids=self.container_ids)
        except Exception as exc:
            row = {'atMs': now_ms(), 'captureStartMs': started, 'captureEndMs': now_ms(),
                   'captureError': str(exc)}
        with self.path.open('a') as output:
            output.write(json.dumps(row) + '\n')

    def _run(self):
        while not self._stop.is_set():
            self.sample()
            self._stop.wait(self.interval_seconds)

    def start(self):
        self._thread = threading.Thread(target=self._run, daemon=True, name='capacity-evidence')
        self._thread.start()

    def close(self):
        if self._closed:
            return
        self._closed = True
        self._stop.set()
        if self._thread:
            self._thread.join()
        self.sample()
