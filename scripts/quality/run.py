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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--rate', type=int, default=10)
    parser.add_argument('--duration-seconds', type=int, default=600)
    parser.add_argument('--fault', action='store_true')
    parser.add_argument('--preallocated-vus', type=int, default=100)
    parser.add_argument('--output-root', default='live-drill/quality-baseline')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,48}', args.run_id):
        parser.error('invalid run ID')
    if args.rate <= 0 or args.duration_seconds <= 0 or (args.fault and args.duration_seconds < 550):
        parser.error('positive rate/duration required; fault duration must be >= 550 seconds')
    if not 1 <= args.preallocated_vus <= 650:
        parser.error('preallocated-vus must be between 1 and 650')
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
    cmd = ['k6', 'run', '--quiet', '--log-format', 'raw', '--console-output', str(directory/'attempts.jsonl'),
           '-e', f'RUN_ID={args.run_id}', '-e', f'RATE={args.rate}',
           '-e', f'DURATION_SECONDS={args.duration_seconds}',
           '-e', f'PREALLOCATED_VUS={args.preallocated_vus}',
           '-e', f'OUTPUT_SUMMARY={directory / "summary.json"}', 'scripts/k6/quality-payment.js']
    evidence.write_json(directory/'command.json', {'argv': cmd, 'cwd': str(evidence.ROOT),
                        'rate': args.rate, 'durationSeconds': args.duration_seconds, 'fault': args.fault,
                        'composeProject': os.environ.get('COMPOSE_PROJECT_NAME', 'docker'),
                        'outputRoot': args.output_root, 'preallocatedVUs': args.preallocated_vus})
    start_ms = mark('load_start')
    log = (directory/'k6.log').open('w')
    process = subprocess.Popen(cmd, cwd=evidence.ROOT, stdout=log, stderr=subprocess.STDOUT)
    stop_monitor = threading.Event()

    def monitor():
        with (directory/'resources.jsonl').open('a') as out:
            while not stop_monitor.is_set():
                try:
                    sample = evidence.sample_resources(containers, process.pid)
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
            stop_monitor.set()
            monitor_thread.join(timeout=25)
            log.close()
            evidence.write_json(directory/'run.json', {'runId': args.run_id, 'verdict': 'INCONCLUSIVE',
                'executionError': execution_error, 'note': 'post-run verification not yet complete'})
            evidence.checksums(directory)
    if execution_error:
        return 2
    mark('load_finished', exitCode=process.returncode)
    raw_events = [json.loads(line) for line in (directory/'attempts.jsonl').read_text().splitlines() if line.strip()]
    summary = json.loads((directory/'summary.json').read_text())
    report = results.analyze(raw_events, summary, args.run_id, args.rate, args.duration_seconds)
    evidence.write_json(directory/'result.json', report)
    settle_deadline = report['actualLastStartMs']+600000
    while True:
        after = settlement.capture(attempts_report=directory/'result.json')
        verdict = settlement.verify(report, before, after)
        evidence.write_json(directory/'after.json', after)
        evidence.write_json(directory/'settlement.json', verdict)
        if verdict['verdict']=='PASS' or verdict['unknownAcceptance'] or now_ms() >= settle_deadline:
            break
        # Expose every observation; never replace a failure with an unexplained success.
        mark('settlement_pending', verdict=verdict['verdict'], counts=verdict['counts'])
        time.sleep(5)
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
        'sourceChanged': bool(source_changed), 'changedSources': source_changed,
        'note': 'Fault run quality metrics describe user impact, not normal-load SLO compliance' if args.fault else ''})
    evidence.checksums(directory)
    return exit_code


if __name__=='__main__':
    raise SystemExit(main())
