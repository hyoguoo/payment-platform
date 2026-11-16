#!/usr/bin/env python3
"""Reproducible local run; never resets data or changes payment states."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import evidence
import results
import settlement


def now_ms():
    return int(time.time() * 1000)


def epoch_ms(value):
    if not value:
        return None
    return int(datetime.fromisoformat(value).replace(tzinfo=timezone.utc).timestamp()*1000)


def stock_uuid(order_id, product_id):
    digest = hashlib.md5(f'stock-commit:{order_id}:{product_id}'.encode()).digest()
    return str(uuid.UUID(bytes=digest, version=3))


def recovered_orders(snapshot, target_ids):
    events = {e['orderId']: e for e in snapshot['paymentEvents']}
    commits = {e['eventUuid']: e for e in snapshot['stockCommitDedupe']}
    done_at = {}
    for h in snapshot['paymentHistories']:
        if h['currentStatus'] == 'DONE':
            stamp = epoch_ms(h['changeStatusAt'])
            done_at[h['orderId']] = min(done_at.get(h['orderId'], stamp), stamp)
    orders = {}
    for row in snapshot['paymentOrders']:
        orders.setdefault(row['orderId'], []).append(row)
    found = {}
    for order_id in target_ids:
        if events.get(order_id, {}).get('status') != 'DONE' or order_id not in done_at:
            continue
        rows = orders.get(order_id, [])
        if not rows:
            continue
        marks = [commits.get(stock_uuid(order_id, row['productId'])) for row in rows]
        if all(m is not None for m in marks):
            found[order_id] = {'doneAtMs': done_at[order_id],
                'stockAppliedAtMs': max(epoch_ms(m['createdAt']) for m in marks),
                'observedAtMs': now_ms()}
    return found


def ensure_payment_running(container_id):
    running = evidence.command(['docker', 'inspect', '--format', '{{.State.Running}}', container_id])
    if running != 'true':
        evidence.command(['docker', 'start', container_id])
        return True
    return False


def payment_container():
    ids = evidence.command(evidence.COMPOSE + ['ps', '-q', 'payment-service']).splitlines()
    if len(ids) != 1 or not ids[0].strip():
        raise RuntimeError('exactly one running payment-service instance is required before load')
    return ids[0]


def fault_observation_pending(killed, restart, health, evaluated, current):
    if killed is None:
        return False
    if restart is None:
        return current < killed + 420000
    if health is None:
        return current < restart + 300000
    return not evaluated and current < health + 320000


def observe_health_cohort(initial_ids, killed_at):
    try:
        health = evidence.get_json('http://localhost:8080/actuator/health', timeout=1)
    except Exception:
        return None
    if health.get('status') != 'UP':
        return None
    observed_at = now_ms()
    # Failure here propagates; health/cohort are published together, never partially.
    snapshot = settlement.capture()
    target_ids = set(initial_ids)
    for h in snapshot['paymentHistories']:
        if h['currentStatus'] == 'IN_PROGRESS' and killed_at <= epoch_ms(h['changeStatusAt']) <= observed_at:
            target_ids.add(h['orderId'])
    return observed_at, snapshot, target_ids


def combined_verdict(quality, settled, fault):
    if fault is None:
        verdict = 'FAIL' if 'FAIL' in (quality, settled) else 'INCONCLUSIVE' if 'INCONCLUSIVE' in (quality, settled) else 'PASS'
    elif (fault.get('detectionEvaluated', True) and not fault['detectionPass']) or fault.get('healthFailed') or settled == 'FAIL':
        verdict = 'FAIL'
    elif not fault['initialOrderIds'] or not fault['observationsComplete'] or not fault.get('detectionEvaluated', True):
        verdict = 'INCONCLUSIVE'
    elif not fault['recoveryPass']:
        verdict = 'FAIL'
    elif fault.get('loadMaintainedThroughRecovery') is not True:
        verdict = 'INCONCLUSIVE'
    else:
        verdict = settled
    return verdict, {'PASS': 0, 'FAIL': 1, 'INCONCLUSIVE': 2}[verdict]


def load_covers_recovery(report, recovery, killed_at):
    """A living k6 process does not prove that new requests are still arriving."""
    first, last = report.get('actualFirstStartMs'), report.get('actualLastStartMs')
    return bool(recovery and first is not None and last is not None and killed_at is not None
                and first <= killed_at and report.get('goals', {}).get('arrival') == 'PASS'
                and max(row['observedAtMs'] for row in recovery.values()) <= last)


def settlement_needs_observation(report, verdict, snapshot=None):
    """Arrival validity cannot recover after load; business work still can."""
    if verdict.get('unknownAcceptance') and snapshot is not None:
        pending, failures = [], []
        settlement._global_residue(snapshot, 'after', pending, failures)
        # Unknown receipt remains inconclusive; it must not hide known work.
        return bool(verdict['failures'] or pending or failures)
    # verify() adds one pending reason for immutable attempt-report validity.
    validity_pending = 1 if report.get('validityIssues') else 0
    return bool(verdict['failures'] or len(verdict['pending']) > validity_pending)


class AttemptFeed:
    """Tail complete k6 JSON lines independently of injection and HTTP observation."""
    def __init__(self, path, consumer):
        self.path, self.consumer = Path(path), consumer
        self.offset, self.partial = 0, ''
        self.errors = []
        self.stop = threading.Event()
        self.thread = None

    def pump(self):
        if not self.path.exists():
            return
        with self.path.open() as source:
            source.seek(self.offset)
            chunk = source.read()
            self.offset = source.tell()
        lines = (self.partial + chunk).split('\n')
        self.partial = lines.pop()
        for line in lines:
            if not line.strip():
                continue
            try:
                row = json.loads(line)
                if row.get('kind') == 'finish':
                    self.consumer.submit(row)
            except Exception as exc:
                self.errors.append(str(exc))

    def start(self):
        def follow():
            while not self.stop.is_set():
                try:
                    self.pump()
                except Exception as exc:
                    self.errors.append(str(exc))
                    return
                self.stop.wait(.02)
        self.thread = threading.Thread(target=follow, daemon=True)
        self.thread.start()

    def close(self):
        self.stop.set()
        if self.thread:
            self.thread.join()
        self.pump()
        if self.partial.strip():
            self.errors.append('truncated final attempt record')


def analyze_mode(events, summary, run_id, rate, duration, mode, phase="measurement", **captured):
    if mode == 'legacy':
        report = results.analyze(events, summary, run_id, rate, duration)
        report['mode'] = mode
        report['phase'] = phase
        return report
    import throughput
    report = throughput.analyze(events, summary, run_id, rate, duration, mode, phase=phase, **captured)
    report['phase'] = phase
    return report


def read_jsonl(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()] if path.exists() else []


def load_command(args, directory):
    return ['k6', 'run', '--quiet', '--log-format', 'raw', '--console-output', str(directory/'attempts.jsonl'),
           '-e', f'RUN_ID={args.run_id}', '-e', f'RATE={args.rate}',
           '-e', f'DURATION_SECONDS={args.duration_seconds}',
           '-e', f'PREALLOCATED_VUS={args.preallocated_vus}',
           '-e', f'MAX_VUS={args.max_vus}', '-e', f'TEST_MODE={args.mode}',
           '-e', f'RUN_PHASE={args.phase}',
           '-e', f'OUTPUT_SUMMARY={directory / "summary.json"}', 'scripts/k6/quality-payment.js']


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--rate', type=int, default=10)
    parser.add_argument('--duration-seconds', type=int, default=600)
    parser.add_argument('--phase', choices=('warmup', 'measurement'), default='measurement')
    parser.add_argument('--mode', choices=('legacy', 'capacity', 'user-quality'), default='legacy')
    parser.add_argument('--max-vus', type=int, default=650)
    parser.add_argument('--observer-workers', type=int, default=32)
    parser.add_argument('--fault', action='store_true')
    parser.add_argument('--preallocated-vus', type=int, default=100)
    parser.add_argument('--output-root', default='live-drill/quality-baseline')
    args = parser.parse_args(argv)
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,48}', args.run_id):
        parser.error('invalid run ID')
    if args.rate <= 0 or args.duration_seconds <= 0 or (args.fault and args.duration_seconds < 550):
        parser.error('positive rate/duration required; fault duration must be >= 550 seconds')
    if not 1 <= args.preallocated_vus <= args.max_vus <= 650:
        parser.error('require 1 <= preallocated-vus <= max-vus <= 650')
    if not 1 <= args.observer_workers <= 256:
        parser.error('observer-workers must be between 1 and 256')
    if args.fault and args.mode != 'legacy':
        parser.error('fault recovery is currently supported only in legacy mode')
    return args


def main():
    args = parse_args()
    directory = evidence.ROOT / args.output_root / args.run_id
    directory.mkdir(parents=True, exist_ok=False)
    events = []

    def mark(kind, **fields):
        event = {'kind': kind, 'atMs': now_ms(), **fields}
        events.append(event)
        with (directory / 'timeline.jsonl').open('a') as output:
            output.write(json.dumps(event, ensure_ascii=False) + '\n')
        print(json.dumps(event, ensure_ascii=False), flush=True)
        return event['atMs']

    containers = evidence.source_manifest(directory)
    before = settlement.capture()
    evidence.write_json(directory / 'before.json', before)
    pending, failures = [], []
    settlement._global_residue(before, 'before', pending, failures)
    if pending or failures:
        raise RuntimeError('previous automatic work or quarantined orders remain; do not overwrite or reset')
    payment_id = payment_container()
    cmd = load_command(args, directory)
    evidence.write_json(directory/'command.json', {'argv': cmd, 'cwd': str(evidence.ROOT),
                        'rate': args.rate, 'durationSeconds': args.duration_seconds, 'fault': args.fault,
                        'composeProject': os.environ.get('COMPOSE_PROJECT_NAME', 'docker'),
                        'outputRoot': args.output_root, 'preallocatedVUs': args.preallocated_vus,
                        'mode': args.mode, 'phase': args.phase, 'maxVUs': args.max_vus,
                        'capacitySampleIntervalSeconds': 10 if args.mode != 'legacy' else None,
                        'observerWorkers': args.observer_workers if args.mode == 'user-quality' else 0,
                        'pollIntervalMs': 1000 if args.mode == 'user-quality' else 500 if args.mode == 'legacy' else None,
                        'maxPolls': 30 if args.mode == 'user-quality' else None})
    start_ms = mark('load_start')
    log = (directory/'k6.log').open('w')
    observer = feed = sampler = None
    try:
        if args.mode != 'legacy':
            import capacity
            sampler = capacity.CapacitySampler(directory/'capacity-samples.jsonl', capacity.watermark(before),
                                               stock_baseline=len(before['stockCommitDedupe']),container_ids=containers)
            sampler.start()
        process = subprocess.Popen(cmd, cwd=evidence.ROOT, stdout=log, stderr=subprocess.STDOUT)
        if args.mode == 'user-quality':
            from observer import Observer
            observer = Observer('http://localhost:8090', directory/'observations.jsonl',
                                max_inflight=args.observer_workers)
            feed = AttemptFeed(directory/'attempts.jsonl', observer)
            feed.start()
    except Exception:
        if 'process' in locals() and process.poll() is None:
            process.terminate()
            process.wait(timeout=10)
        if observer:
            observer.close()
        if sampler:
            sampler.close()
        log.close()
        raise
    stop_monitor = threading.Event()

    def monitor():
        with (directory/'resources.jsonl').open('a') as out:
            while not stop_monitor.is_set():
                try:
                    resource_pid = process.pid if process.poll() is None else os.getpid()
                    sample = evidence.sample_resources(containers, resource_pid)
                    sample['processRole'] = 'k6' if resource_pid == process.pid else 'runner'
                    sample['k6Running'] = process.poll() is None
                    sample['runnerProcess'] = evidence.command(
                        ['ps', '-p', str(os.getpid()), '-o', 'pid=,pcpu=,rss='])
                    if observer:
                        sample['observer'] = observer.stats()
                except Exception as exc:
                    sample = {'atMs': now_ms(), 'captureError': str(exc)}
                out.write(json.dumps(sample) + '\n')
                out.flush()
                stop_monitor.wait(10)
    monitor_thread = threading.Thread(target=monitor, daemon=True)
    monitor_thread.start()
    killed_at = restarted_at = health_at = first_firing = None
    killed = False
    last_alert_observation = None
    initial_ids = set()
    targets = set()
    recovery = {}
    recovery_complete = False
    deadline_evaluated = False
    next_observation = time.monotonic()
    execution_error = None
    try:
        while process.poll() is None or (args.fault and fault_observation_pending(killed_at, restarted_at, health_at, deadline_evaluated, now_ms())):
            elapsed = (now_ms() - start_ms) / 1000
            if args.fault and not killed and elapsed >= 60:
                mark('kill_requested', container=payment_id)
                evidence.command(['docker', 'kill', '--signal', 'KILL', payment_id])
                killed_at = mark('payment_stopped')
                killed = True
                snapshot = settlement.capture()
                evidence.write_json(directory/'after-kill.json', snapshot)
                accepted = {r['orderId'] for r in snapshot['paymentOutbox']}
                initial_ids = {e['orderId'] for e in snapshot['paymentEvents']
                               if e['orderId'] in accepted and e['status'] not in ('DONE','FAILED','EXPIRED','QUARANTINED')}
                targets.update(initial_ids)
                mark('recovery_cohort_initial', count=len(initial_ids), orderIds=sorted(initial_ids))
            if args.fault and killed and restarted_at is None:
                try:
                    alerts = evidence.get_json('http://localhost:9090/api/v1/alerts')
                    last_alert_observation = now_ms()
                    with (directory/'alert-observations.jsonl').open('a') as f:
                        f.write(json.dumps({'atMs': now_ms(), 'response': alerts})+'\n')
                    if first_firing is None and any(a['state']=='firing' and
                        a['labels'].get('alertname')=='ServiceDown' and
                        a['labels'].get('job')=='payment-service' for a in alerts['data']['alerts']):
                        first_firing = mark('service_down_firing')
                except Exception as exc:
                    mark('alert_capture_error', error=str(exc))
                if now_ms() - killed_at >= 120000:
                    restarted_at = mark('restart_requested')
                    evidence.command(['docker','start',payment_id])
                    mark('restart_command_succeeded')
            if restarted_at is not None and health_at is None:
                observation = observe_health_cohort(initial_ids, killed_at)
                if observation is not None:
                    health_at, snapshot, targets = observation
                    mark('payment_health_up', observedAtMs=health_at)
                    evidence.write_json(directory/'at-health.json', snapshot)
                    mark('recovery_cohort_fixed', count=len(targets), orderIds=sorted(targets))
            if health_at is not None and not deadline_evaluated and time.monotonic() >= next_observation:
                snapshot = settlement.capture()
                recovery = recovered_orders(snapshot, targets)
                evidence.write_json(directory/'recovery-observation.json', snapshot)
                mark('recovery_progress', targetCount=len(targets), recoveredCount=len(recovery))
                if targets and len(recovery)==len(targets):
                    recovery_complete = True
                    deadline_evaluated = True
                    mark('recovery_complete')
                elif now_ms() >= health_at+300000:
                    deadline_evaluated = True
                    mark('recovery_deadline_exceeded')
                next_observation = time.monotonic()+10
            time.sleep(1)
    except Exception as exc:
        execution_error = str(exc)
        mark('execution_error', error=execution_error)
    finally:
        try:
            if killed:
                if ensure_payment_running(payment_id):
                    mark('cleanup_restarted_payment')
        except Exception as exc:
            execution_error = (execution_error or '') + '; cleanup restart failed: ' + str(exc)
            mark('cleanup_error', error=str(exc))
        try:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=70)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
                    mark('k6_force_killed')
        finally:
            mark('load_finished', exitCode=process.returncode)
            if feed:
                try:
                    feed.close()
                    observer.close()
                    observer_stats = observer.stats()
                    observer_stats['feedErrors'] = feed.errors
                    evidence.write_json(directory/'observer.json', observer_stats)
                    mark('observation_finished', stats=observer_stats)
                except Exception as exc:
                    execution_error = (execution_error or '') + '; observer: ' + str(exc)
            stop_monitor.set()
            monitor_thread.join(timeout=25)
            log.close()
            evidence.write_json(directory/'run.json', {'runId': args.run_id, 'verdict': 'INCONCLUSIVE',
                'executionError': execution_error, 'note': 'post-run verification not yet complete'})
            evidence.checksums(directory)
    if execution_error:
        if sampler:
            sampler.close()
        evidence.checksums(directory)
        return 2
    try:
        raw_events = read_jsonl(directory/'attempts.jsonl')
        summary = json.loads((directory/'summary.json').read_text())
        observed = read_jsonl(directory/'observations.jsonl') if observer else None
        observed_stats = json.loads((directory/'observer.json').read_text()) if observer else None
        captured = dict(phase=args.phase, observations=observed, observer_stats=observed_stats, before=before)
        report = analyze_mode(raw_events, summary, args.run_id, args.rate, args.duration_seconds,
                              args.mode, **captured)
        evidence.write_json(directory/'result.json', report)
        settle_deadline = (report['actualLastStartMs'] or start_ms)+600000
        while True:
            after = settlement.capture(attempts_report=directory/'result.json')
            after['captureCompletedAtMs'] = now_ms()
            verdict = settlement.verify(report, before, after)
            evidence.write_json(directory/'after.json', after)
            evidence.write_json(directory/'settlement.json', verdict)
            if (verdict['verdict']=='PASS'
                    or not settlement_needs_observation(report, verdict, after) or now_ms() >= settle_deadline):
                break
            # Expose every observation; never replace a failure with an unexplained success.
            mark('settlement_pending', verdict=verdict['verdict'], counts=verdict['counts'])
            time.sleep(5)
        if sampler:
            sampler.close()
            report = analyze_mode(raw_events, summary, args.run_id, args.rate, args.duration_seconds,
                                  args.mode, **captured, after=after, settled=verdict,
                                  trend_samples=read_jsonl(directory/'capacity-samples.jsonl'))
            verdict = settlement.verify(report, before, after)
            evidence.write_json(directory/'result.json', report)
            evidence.write_json(directory/'settlement.json', verdict)
        fault = None
        if args.fault:
            limit = health_at+300000 if health_at else 0
            within = {key: val for key,val in recovery.items()
                      if max(val['doneAtMs'], val['stockAppliedAtMs'], val['observedAtMs']) <= limit}
            initial_restarted = all(key in within and within[key]['doneAtMs'] >= (restarted_at or 0)
                                    for key in initial_ids)
            fault = {'killedAtMs': killed_at, 'firstFiringMs': first_firing, 'restartRequestedMs': restarted_at,
                     'healthUpMs': health_at, 'targetOrderIds': sorted(targets), 'initialOrderIds': sorted(initial_ids),
                     'recoveredOrders': recovery, 'detectionPass': bool(first_firing and first_firing-killed_at<=120000),
                     'recoveryPass': bool(initial_ids and targets and len(within)==len(targets) and initial_restarted),
                     'recoveryDeadlineMs': limit, 'observationsComplete': recovery_complete or deadline_evaluated,
                     'detectionEvaluated': bool(first_firing or (last_alert_observation and killed_at and last_alert_observation >= killed_at+120000)),
                     'healthFailed': bool(restarted_at and health_at is None and now_ms() >= restarted_at+300000)}
            fault['loadMaintainedThroughRecovery'] = load_covers_recovery(report, recovery, killed_at)
            fault['actualLastArrivalMs'] = report.get('actualLastStartMs')
            evidence.write_json(directory/'fault.json', fault)
        end_ms = mark('run_finished', quality=report['verdict'], settlement=verdict['verdict'])
        evidence.prometheus_range(directory, start_ms, end_ms)
        overall, exit_code = combined_verdict(report['verdict'], verdict['verdict'], fault)
        source_changed = evidence.changed_sources(directory)
        if source_changed and overall == 'PASS':
            overall, exit_code = 'INCONCLUSIVE', 2
        evidence.write_json(directory/'run.json', {'runId': args.run_id, 'verdict': overall, 'exitCode': exit_code,
            'quality': report['verdict'], 'settlement': verdict['verdict'], 'fault': bool(args.fault),
            'mode': args.mode, 'phase': args.phase,
            'sourceChanged': bool(source_changed), 'changedSources': source_changed,
            'note': 'Fault run quality metrics describe user impact, not normal-load SLO compliance' if args.fault else ''})
        return exit_code
    finally:
        if sampler:
            sampler.close()
        evidence.checksums(directory)



if __name__=='__main__':
    raise SystemExit(main())
