"""Local-only evidence capture for the quality baseline (standard library only)."""
import hashlib
import json
import platform
import subprocess
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ['docker', 'compose'] + [item for name in ('infra', 'apps', 'observability', 'smoke', 'quality')
                                 for item in ('-f', f'docker/docker-compose.{name}.yml')]
ENV_KEYS = {'JAVA_TOOL_OPTIONS', 'SPRING_PROFILES_ACTIVE', 'PG_GATEWAY_TYPE',
            'PG_GATEWAY_FAKE_LATENCY_MIN_MILLIS', 'PG_GATEWAY_FAKE_LATENCY_MAX_MILLIS',
            'PG_GATEWAY_FAKE_FAIL_RATE', 'MANAGEMENT_TRACING_SAMPLING_PROBABILITY',
            'RECONCILER_IN_FLIGHT_TIMEOUT_SECONDS', 'RECONCILER_AWAITING_RESULT_TIMEOUT_SECONDS',
            'RECONCILER_FIXED_DELAY_MS', 'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE'}


def command(args, timeout=45):
    return subprocess.run(args, cwd=ROOT, capture_output=True, text=True, check=True,
                          timeout=timeout).stdout.strip()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def get_json(url, timeout=5):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return json.load(response)


def source_manifest(directory):
    """Only known source paths; never capture .env, full process env, or PG payloads."""
    files = command(['git', 'ls-files', '--cached', '--others', '--exclude-standard']).splitlines()
    sources = [p for p in files if p.startswith(('scripts/quality/', 'scripts/k6/quality-',
               'docker/', 'observability/', 'docs/quality/README.md',
               'docs/quality/RUNBOOK.md')) or p == 'scripts/compose-up.sh']
    hashes = {}
    for name in sources:
        path = ROOT / name
        if path.is_file() and not any(x in name for x in ('.env', 'secret', 'results/', '__pycache__')):
            hashes[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    write_json(directory / 'source-hashes.json', hashes)
    diff = command(['git', 'diff', '--', 'scripts/compose-up.sh', 'scripts/quality', 'scripts/k6',
                    'docker', 'observability'])
    (directory / 'source.patch').write_text(diff + '\n')
    # Untracked implementation also needs to be reproducible after subsequent edits.
    for name in hashes:
        if name.startswith(('scripts/quality/', 'scripts/k6/quality-', 'docker/docker-compose.quality',
                            'observability/alertmanager/quality')) or name == 'scripts/compose-up.sh':
            target = directory / 'source' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / name).read_bytes())
    ids = command(COMPOSE + ['ps', '-a', '-q']).splitlines()
    inspected = json.loads(command(['docker', 'inspect', *ids])) if ids else []
    containers = []
    for c in inspected:
        env = dict(x.split('=', 1) for x in c['Config']['Env'] if '=' in x)
        containers.append({'name': c['Name'].lstrip('/'), 'id': c['Id'], 'imageId': c['Image'],
            'service': c['Config']['Labels'].get('com.docker.compose.service'),
            'env': {k: v for k, v in env.items() if k in ENV_KEYS},
            'memoryLimitBytes': c['HostConfig']['Memory'], 'nanoCpus': c['HostConfig']['NanoCpus'],
            'ports': c['HostConfig']['PortBindings'],
            'mounts': [{'type': m['Type'], 'name': m.get('Name'), 'destination': m['Destination']}
                       for m in c['Mounts']]})
    write_json(directory / 'environment.json', {'capturedAtMs': int(time.time()*1000),
        'gitCommit': command(['git', 'rev-parse', 'HEAD']),
        'workingTree': command(['git', 'status', '--short']),
        'host': platform.platform(), 'hostMemory': command(['sysctl', '-n', 'hw.memsize']),
        'hostCpu': command(['sysctl', '-n', 'hw.ncpu']),
        'docker': command(['docker', 'info', '--format', '{{json .NCPU}} {{json .MemTotal}}']),
        'dockerVersion': command(['docker', 'version', '--format', '{{.Server.Version}}']),
        'composeVersion': command(['docker', 'compose', 'version', '--short']),
        'k6Version': command(['k6', 'version']), 'containers': containers})
    return ids


def sample_resources(ids, k6_pid):
    stats = command(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *ids], timeout=20)
    return {'atMs': int(time.time()*1000), 'containers': [json.loads(s) for s in stats.splitlines()],
            'hostVm': command(['vm_stat']), 'hostSwap': command(['sysctl', 'vm.swapusage']),
            'process': command(['ps', '-p', str(k6_pid), '-o', 'pid=,pcpu=,rss='])}


def changed_sources(directory):
    hashes = json.loads((directory / 'source-hashes.json').read_text())
    return [name for name, digest in hashes.items()
            if not (ROOT / name).is_file() or hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != digest]


def prometheus_range(directory, start_ms, end_ms):
    queries = {'service_up': 'up', 'alerts': 'ALERTS',
        'jvm_heap_used': 'jvm_memory_used_bytes{area="heap"}',
        'process_cpu': 'process_cpu_usage', 'hikari_pending': 'hikaricp_connections_pending',
        'hikari_active': 'hikaricp_connections_active',
        'kafka_lag': 'kafka_consumergroup_lag',
        'http_requests': 'sum by (job,status) (rate(http_server_requests_seconds_count[1m]))'}
    for name, query in queries.items():
        params = urllib.parse.urlencode({'query': query, 'start': start_ms/1000,
                                        'end': end_ms/1000, 'step': 15})
        try:
            data = get_json('http://localhost:9090/api/v1/query_range?' + params)
        except Exception as exc:
            data = {'captureError': str(exc)}
        write_json(directory / f'prometheus-{name}.json', data)


def checksums(directory):
    hashes = {str(p.relative_to(directory)): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in sorted(directory.rglob('*')) if p.is_file() and p.name != 'SHA256SUMS.json'}
    write_json(directory / 'SHA256SUMS.json', hashes)
