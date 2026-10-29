"""Evidence-based verdicts for the split-injection throughput modes.

This report is intentionally separate from schema 1's legacy 500 ms polling
verdict. A missing final snapshot, settlement, or stability window cannot be
turned into a capacity PASS by a fast k6 summary alone.
"""
import math
import re
from datetime import datetime, timezone

import results
from capacity import JOBS


POLICY = {
    'arrivalScheduleLagLimitMs':100,
    'arrivalClockRoundingToleranceMs':1,
    'arrivalTiming': 'dense iteration sequence, exact planned count or one extra, no drops; each actual start within -1..100 ms of its nominal arrival',
    'checkoutP95LimitMs': 500,
    'confirmP95LimitMs': 500,
    'userCompletionP95LimitMs': 3000,
    'userCompletionP99LimitMs': 5000,
    'userPollIntervalMs': 1000,
    'userMaxPolls': 30,
    'userDeadlineOffsetMs': 31000,
    'stabilityWindow': 'last half of load; at least 60 s, four samples, no gap > 15 s',
    'stableBacklog': 'in latter load window, late-half pending/oldest min and max do not exceed early-half; completion deficit <= early pending range',
    'memoryScope': 'all 5 app heaps, all 20 project container Docker memory samples, host swap; host vm_stat also saved as raw resources',
    'originalScrapeFreshnessLimitMs':30000,
    'stockCounting': 'one product, quantity 1 per purchase; product stock_commit_dedupe count minus fully settled warmup baseline',
    'stableMemory': 'for every service, late-half heap minimum and maximum <= early-half; bounded GC oscillation allowed; no fixed byte allowance',
    'qualityCoverage': 'all started purchases are percentile denominator; unobserved/late DONE are +infinity, so a small upper tail may miss the UI deadline',
    'doneTime': 'payment_history.change_status_at on finally committed rows, not commit-instant instrumentation',
}


def _issue_unique(items):
    return list(dict.fromkeys(items))


def _percentile(values, pct, denominator):
    if denominator <= 0:
        return None
    ordered = sorted(values)
    rank = math.ceil(denominator * pct / 100)
    return ordered[rank - 1] if rank <= len(ordered) else None


def _epoch_ms(value):
    if not isinstance(value, str) or not value:
        return None
    try:
        moment = datetime.fromisoformat(value.replace('Z', '+00:00'))
        if moment.tzinfo is None:
            moment = moment.replace(tzinfo=timezone.utc)
        return int(moment.timestamp() * 1000)
    except ValueError:
        return None


def _arrival_timing(events,run_id,rate,t0,t1,expected):
    issues=[]
    starts=[row for row in events if isinstance(row,dict) and row.get('kind')=='start' and row.get('runId')==run_id]
    indices=[];lags=[]
    if t0 is None or t1 is None:
        return {'timingStatus':'NOT_MEASURED'},['arrival timing window unavailable']
    for row in starts:
        match=re.fullmatch(re.escape(run_id)+r'-(\d+)',str(row.get('attemptId','')))
        if not match or type(row.get('startMs')) is not int:
            issues.append('arrival iteration sequence or timestamp invalid')
            continue
        index=int(match[1])
        indices.append(index)
        if index > expected:
            issues.append('arrival iteration sequence exceeds planned count')
            continue
        lags.append(row['startMs']-t0-index*1000/rate)
    if sorted(indices) != list(range(len(starts))):
        issues.append('arrival iteration sequence is not dense from zero')
    if lags and (min(lags) < -1.000001 or max(lags) > 100.000001):
        issues.append('arrival scheduling lag outside -1..100 ms; generator timing inconclusive')
    start_times=[r['startMs'] for r in starts if type(r.get('startMs')) is int]
    return {'timingStatus':'VALID' if not issues else 'INCONCLUSIVE',
            'maxScheduleLagMs':round(max(lags),3) if lags else None,
            'minScheduleLagMs':round(min(lags),3) if lags else None,
            'scheduleLagP99Ms':_percentile(lags,99,len(starts)),
            'nominalWindowEndMs':t1,
            'actualLastStartAfterEndMs':max(0,max(start_times,default=t1)-t1),
            'lateBoundaryStarts':sum(at>=t1 for at in start_times),
            'scheduleLagLimitMs':100,'clockRoundingToleranceMs':1},issues


def _mean(pair):
    return sum(pair) / len(pair)


def _stability(samples, t0, t1, issues):
    if samples is None:
        issues.append('missing in-load trend samples')
        return {'status': 'INSUFFICIENT', 'reason': 'no samples'}
    latter = []
    for number, sample in enumerate(samples):
        if not isinstance(sample, dict) or sample.get('captureError'):
            issues.append(f'trend sample {number}: capture error')
            continue
        at, start, end = (sample.get(key) for key in ('atMs', 'captureStartMs', 'captureEndMs'))
        if not all(isinstance(x, int) for x in (at, start, end)) or not start <= at <= end:
            issues.append(f'trend sample {number}: invalid capture interval')
            continue
        if t0 + (t1 - t0) / 2 <= at <= t1 and end <= t1:
            latter.append(sample)
    latter.sort(key=lambda row: row['atMs'])
    if len(latter) < 4 or latter[-1]['atMs'] - latter[0]['atMs'] < 60_000:
        issues.append('stability window needs >=4 in-load samples spanning >=60 s')
        return {'status': 'INSUFFICIENT', 'samples': len(latter)}
    if any(b['atMs'] - a['atMs'] > 15_000 or b['atMs'] <= a['atMs']
           for a, b in zip(latter, latter[1:])):
        issues.append('stability samples have gap >15 s or duplicate time')
        return {'status': 'INSUFFICIENT', 'samples': len(latter)}
    fields = ('orders', 'done', 'pending','stockCommitted','stockPending','pipelinePending')
    for row in latter:
        if (any(type(row.get(key)) is not int or row[key] < 0 for key in fields)
                or not isinstance(row.get('oldestPendingAgeMs'), (int, float))
                or not math.isfinite(row['oldestPendingAgeMs']) or row['oldestPendingAgeMs'] < 0):
            issues.append('stability sample missing nonnegative order/backlog counts')
            return {'status': 'INSUFFICIENT', 'samples': len(latter)}
        if row['done'] > row['orders'] or row['orders'] - row['done'] != row['pending']:
            issues.append('stability sample orders/done/pending inconsistent')
            return {'status': 'INSUFFICIENT', 'samples': len(latter)}
        if (row['stockPending'] != max(0,row['done']-row['stockCommitted'])
                or row['pipelinePending'] != max(0,row['orders']-row['stockCommitted'])):
            issues.append('stock/pipeline pending counters inconsistent')
            return {'status':'INSUFFICIENT','samples':len(latter)}
        coverage=row.get('memoryCoverage') or {}
        memory=row.get('memoryBytes') or {}
        source=row.get('sourceSampleAtMs') or {}
        if (set(coverage.get('heapJobs',[])) != JOBS or coverage.get('containerCount') != 20
                or coverage.get('hostSwap') is not True or not JOBS.issubset(memory)
                or len([k for k in memory if k.startswith('container:')]) != 20
                or 'host:swapUsedBytes' not in memory or set(source) != JOBS
                or any(type(at) is not int or not -1000 <= row['captureStartMs']-at <= 30000
                       for at in source.values())):
            issues.append('missing full pipeline memory coverage or stale original scrape')
            return {'status':'INSUFFICIENT','samples':len(latter)}
    keys = set(latter[0].get('memoryBytes') or {})
    if not keys or any(not isinstance(row.get('memoryBytes'), dict)
                       or set(row['memoryBytes']) != keys
                       or any(not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0
                              for value in row['memoryBytes'].values()) for row in latter):
        issues.append('stability memory samples missing or inconsistent')
        return {'status': 'INSUFFICIENT', 'samples': len(latter)}

    early, late = latter[:2], latter[-2:]
    slopes = {field: _mean([r[field] for r in late]) - _mean([r[field] for r in early])
              for field in ('pending', 'oldestPendingAgeMs','stockPending','pipelinePending')}
    memory_delta = {key: _mean([r['memoryBytes'][key] for r in late]) -
                    _mean([r['memoryBytes'][key] for r in early]) for key in sorted(keys)}
    arrival_gain = latter[-1]['orders'] - latter[0]['orders']
    done_gain = latter[-1]['done'] - latter[0]['done']
    stock_gain=latter[-1]['stockCommitted']-latter[0]['stockCommitted']
    evidence = {'samples': len(latter), 'startMs': latter[0]['atMs'], 'endMs': latter[-1]['atMs'],
                'pendingFirstLast': [latter[0]['pending'], latter[-1]['pending']],
                'oldestAgeFirstLastMs': [latter[0]['oldestPendingAgeMs'],
                                         latter[-1]['oldestPendingAgeMs']],
                'pendingMeanChange': slopes['pending'],
                'oldestAgeMeanChangeMs': slopes['oldestPendingAgeMs'],
                'memoryMeanChangesBytes': memory_delta,
                'arrivalsGain': arrival_gain, 'doneGain': done_gain,'stockCommittedGain':stock_gain,
                'stockPendingFirstLast':[latter[0]['stockPending'],latter[-1]['stockPending']],
                'pipelinePendingFirstLast':[latter[0]['pipelinePending'],latter[-1]['pipelinePending']]}
    if any(latter[index + 1]['orders'] < latter[index]['orders'] or
           latter[index + 1]['done'] < latter[index]['done'] or
           latter[index+1]['stockCommitted'] < latter[index]['stockCommitted']
           for index in range(len(latter) - 1)):
        issues.append('stability cumulative orders/done decrease')
        return dict(evidence, status='INSUFFICIENT')
    if arrival_gain <= 0:
        issues.append('stability window has no incoming purchases')
        return dict(evidence, status='INSUFFICIENT')
    # Compare observed envelopes rather than two GC phases or one queue tick.
    # These are finite-window project criteria, not a proof of unbounded uptime.
    first_segment = latter[:len(latter)//2]
    last_segment = latter[len(latter)//2:]
    def band(rows, field):
        values = [r[field] for r in rows]
        return [min(values), max(values)]
    backlog_bands = {field: [band(first_segment, field), band(last_segment, field)]
                     for field in ('pending', 'oldestPendingAgeMs','stockPending','pipelinePending')}
    memory_bands = {key: [[min(r['memoryBytes'][key] for r in group),
                           max(r['memoryBytes'][key] for r in group)]
                          for group in (first_segment, last_segment)] for key in sorted(keys)}
    within = lambda bands: bands[1][0] <= bands[0][0] and bands[1][1] <= bands[0][1]
    pending_range = backlog_bands['pending'][0][1] - backlog_bands['pending'][0][0]
    evidence.update(backlogBands=backlog_bands, memoryBandsBytes=memory_bands,
                    observedPendingVariation=pending_range)
    pipeline_range=backlog_bands['pipelinePending'][0][1]-backlog_bands['pipelinePending'][0][0]
    stable = (done_gain >= arrival_gain-pending_range and stock_gain >= arrival_gain-pipeline_range
              and all(within(bands) for bands in backlog_bands.values())
              and all(within(bands) for bands in memory_bands.values()))
    growing = (slopes['pending'] > 0 and slopes['oldestPendingAgeMs'] > 0
               and done_gain < arrival_gain
               and (backlog_bands['pending'][1][1] > backlog_bands['pending'][0][1])
               and all(b['pending'] >= a['pending'] and b['oldestPendingAgeMs'] >= a['oldestPendingAgeMs']
                       for a,b in zip(latter,latter[1:])))
    product_growing = (stock_gain < arrival_gain
        and backlog_bands['pipelinePending'][1][1] > backlog_bands['pipelinePending'][0][1]
        and all(b['pipelinePending'] >= a['pipelinePending'] for a,b in zip(latter,latter[1:])))
    return dict(evidence,status='STABLE' if stable else 'GROWING' if growing or product_growing else 'UNRESOLVED')


def _cohort_done(before, after, attempts, t0, t1, issues):
    if before is None or after is None:
        issues.append('missing before/after durable snapshots')
        return {'status': 'NOT_MEASURED', 'doneDuringLoad': None,
                'doneAfterLoad': None, 'drainTimeMs': None}
    before_ids = {row.get('orderId') for row in before.get('paymentEvents', [])}
    target = {row.get('orderId') for row in attempts if row.get('orderId')}
    if target & before_ids:
        issues.append('attempt order existed in before snapshot')
    after_events = {row.get('orderId'): row for row in after.get('paymentEvents', [])}
    done_at = {}
    for row in after.get('paymentHistories', []):
        oid = row.get('orderId')
        if oid not in target or row.get('currentStatus') != 'DONE':
            continue
        stamp = _epoch_ms(row.get('changeStatusAt'))
        if stamp is None:
            issues.append(f'invalid DONE history timestamp: {oid}')
            continue
        if oid in done_at:
            issues.append(f'duplicate DONE history: {oid}')
        done_at[oid] = min(stamp, done_at.get(oid, stamp))
    missing = [oid for oid in target if oid not in after_events]
    if missing:
        issues.append(f'after snapshot lacks attempt orders: {len(missing)}')
    missing_history = [oid for oid in target if after_events.get(oid, {}).get('status') == 'DONE'
                       and oid not in done_at]
    if missing_history:
        issues.append(f'DONE orders without history timestamp: {len(missing_history)}')
    during = sum(t0 <= stamp < t1 for stamp in done_at.values()) if t0 is not None else None
    after_load = sum(stamp >= t1 for stamp in done_at.values()) if t1 is not None else None
    if t0 is not None and any(stamp < t0 for stamp in done_at.values()):
        issues.append('DONE history predates measurement start')
    all_done = bool(target) and len(done_at) == len(target) and all(
        after_events.get(oid, {}).get('status') == 'DONE' for oid in target)
    drain = max(0, max(done_at.values()) - t1) if all_done and t1 is not None else None
    latencies = []
    for attempt in attempts:
        stamp = done_at.get(attempt.get('orderId'))
        confirm_start = attempt.get('confirmStartMs')
        if stamp is not None and isinstance(confirm_start, int):
            if stamp < confirm_start:
                issues.append('recorded DONE predates confirm start; check clock alignment')
            else:
                latencies.append(stamp - confirm_start)
    observed_end = after.get('captureCompletedAtMs')
    observed_drain = (max(0, observed_end-t1) if all_done and isinstance(observed_end, int)
                      and t1 is not None else None)
    return {'status': 'ALL_DONE' if all_done else 'INCOMPLETE',
            'cohortOrders': len(target), 'doneHistories': len(done_at),
            'doneDuringLoad': during, 'doneAfterLoad': after_load,
            'drainTimeMs': drain, 'timeBasis': POLICY['doneTime'],
            'recordedDoneP95Ms': _percentile(latencies, 95, len(attempts)),
            'recordedDoneP99Ms': _percentile(latencies, 99, len(attempts)),
            'recordedDoneLatencyBoundary': 'confirm request start to recorded DONE history time',
            'settlementObservedDrainTimeMs': observed_drain,
            'settlementTimeBasis': 'upper bound from full order/PG/stock snapshot completion; not exact stock application time'}


def _observations(observations, observer_stats, attempts, run_id, issues, evidence_issues):
    if observations is None or observer_stats is None:
        evidence_issues.append('missing user observation records or observer statistics')
        return {}, {'submitted': None, 'finished': None, 'errors': None}
    finals = {}
    poll_starts, poll_results, poll_skips = [], [], []
    for number, row in enumerate(observations):
        if not isinstance(row, dict):
            issues.append(f'observation event {number}: invalid object')
            continue
        if row.get('kind') == 'poll_start':
            poll_starts.append(row)
            continue
        if row.get('kind') == 'poll_result':
            poll_results.append(row)
            continue
        if row.get('kind') == 'poll_skip':
            poll_skips.append(row)
            continue
        if row.get('kind') != 'observation_finish':
            issues.append(f'observation event {number}: invalid kind')
            continue
        aid = row.get('attemptId')
        if aid in finals:
            issues.append(f'duplicate observation finish: {aid}')
        finals[aid] = row
        if row.get('runId') != run_id:
            issues.append(f'observation runId mismatch: {aid}')
    for field, expected in (('pollIntervalMs', 1000), ('maxPolls', 30), ('deadlineOffsetMs', 31000)):
        if observer_stats.get(field) != expected:
            evidence_issues.append(f'observer policy mismatch: {field}')
    by_attempt = {row['attemptId']: row for row in attempts}
    poll_identities = set()
    for poll in poll_starts:
        identity = (poll.get('attemptId'), poll.get('orderId'), poll.get('pollNo'))
        attempt = by_attempt.get(poll.get('attemptId'), {})
        end, number, at = attempt.get('confirmEndMs'), poll.get('pollNo'), poll.get('atMs')
        if (identity in poll_identities or poll.get('orderId') != attempt.get('orderId')
                or type(end) is not int or type(at) is not int or type(number) is not int
                or not 1 <= number <= 30 or not end+number*1000 <= at < end+(number+1)*1000):
            evidence_issues.append('invalid or duplicate scheduled poll')
        poll_identities.add(identity)
    result_ids = [(poll.get('attemptId'), poll.get('orderId'), poll.get('pollNo')) for poll in poll_results]
    if len(set(result_ids)) != len(result_ids) or set(result_ids) != poll_identities:
        evidence_issues.append('poll result/start identities mismatch')
    expected_ids = {row['attemptId'] for row in attempts}
    if set(finals) != expected_ids:
        evidence_issues.append(f'observation finals/attempts mismatch: {len(finals)}/{len(expected_ids)}')
    if (observer_stats.get('submitted') != len(attempts)
            or observer_stats.get('finished') != len(finals)):
        evidence_issues.append('observer submitted/finished counts mismatch attempts/finals')
    if (observer_stats.get('polls') != len(poll_starts)
            or len(poll_results) != len(poll_starts)):
        evidence_issues.append('observer poll start/result counts mismatch statistics')
    capacity_skips = sum(row.get('reason') == 'capacity' for row in poll_skips)
    late_skips = sum(row.get('reason') in ('deadline', 'schedule_late') for row in poll_skips)
    if (capacity_skips != observer_stats.get('capacitySkipped')
            or late_skips != observer_stats.get('lateSkipped')):
        evidence_issues.append('observer poll skip counts mismatch statistics')
    raw_errors = sum(bool(row.get('error')) for row in poll_results)
    if raw_errors != observer_stats.get('errors'):
        evidence_issues.append('observer poll errors mismatch statistics')
    for field in ('feedErrors', 'capacitySkipped', 'lateSkipped', 'rejected', 'unobservable'):
        if observer_stats.get(field) not in (0, None, []):
            evidence_issues.append(f'observer {field}: {observer_stats[field]}')
    if observer_stats.get('incomplete'):
        evidence_issues.append('observer marked incomplete')
    if observer_stats.get('errors') is None:
        evidence_issues.append('observer error count unavailable')
    terminal_results = {}
    for poll in poll_results:
        if (poll.get('httpStatus') == 200 and poll.get('observedStatus') in ('DONE','FAILED','QUARANTINED')
                and not poll.get('late') and not poll.get('error')):
            terminal_results.setdefault(poll.get('attemptId'), []).append(poll)
    starts_per_attempt = {}
    for poll in poll_starts:
        starts_per_attempt[poll.get('attemptId')] = starts_per_attempt.get(poll.get('attemptId'),0)+1
    for aid, final in finals.items():
        attempt = by_attempt.get(aid,{})
        if any(final.get(field) != attempt.get(field) for field in
               ('orderId','confirmStartMs','confirmEndMs','confirmStatus')):
            evidence_issues.append(f'observation identity/confirm metadata mismatch: {aid}')
        if final.get('confirmAccepted') != (attempt.get('confirmStatus') == 202):
            evidence_issues.append(f'observation acceptance mismatch: {aid}')
        if final.get('pollCount') != starts_per_attempt.get(aid,0):
            evidence_issues.append(f'observation poll count mismatch: {aid}')
        if final.get('outcome') == 'UNOBSERVABLE':
            continue
        end, deadline, finished = attempt.get('confirmEndMs'), final.get('deadlineMs'), final.get('atMs')
        if type(end) is not int or deadline != end+31000 or type(finished) is not int:
            evidence_issues.append(f'observation deadline/finish invalid: {aid}')
            continue
        terminals = terminal_results.get(aid,[])
        outcome = final.get('outcome')
        if outcome in ('DONE','FAILED','QUARANTINED'):
            if not any(p.get('orderId') == final.get('orderId') and p.get('atMs') == finished
                       and p.get('observedStatus') == outcome for p in terminals):
                evidence_issues.append(f'terminal observation lacks on-time poll result: {aid}')
        elif outcome == 'TIMEOUT':
            if terminals or finished < deadline:
                evidence_issues.append(f'TIMEOUT contradicts terminal poll/deadline: {aid}')
        else:
            evidence_issues.append(f'invalid observation outcome: {aid}')
    return finals, {'submitted': observer_stats.get('submitted'),
                    'finished': observer_stats.get('finished'),
                    'errors': observer_stats.get('errors'),
                    'capacitySkipped': observer_stats.get('capacitySkipped'),
                    'lateSkipped': observer_stats.get('lateSkipped'),
                    'feedErrors': observer_stats.get('feedErrors')}


def analyze(events, summary, run_id, rate, duration_seconds, mode, *, observations=None,
            observer_stats=None, before=None, after=None, trend_samples=None, settled=None, phase="measurement"):
    if mode not in ('capacity', 'user-quality'):
        raise ValueError('throughput.analyze accepts capacity or user-quality mode only')
    base = results.analyze(events, summary, run_id, rate, duration_seconds)
    validity = list(base['validityIssues'])
    arrival,arrival_issues=_arrival_timing(events,run_id,rate,base['measurementStartMs'],base['measurementEndMs'],base['counts']['expected'])
    if not arrival_issues:
        validity=[issue for issue in validity if not issue.startswith('more than one boundary start:')]
    validity.extend(arrival_issues)
    evidence_issues = []
    if phase not in ('warmup','measurement'):
        raise ValueError('invalid run phase')
    if any(not isinstance(row,dict) or row.get('mode') != mode or row.get('phase') != phase for row in events):
        validity.append('raw attempt mode/phase mismatch or missing; do not relabel legacy evidence')
    attempts = base['attempts']
    finishes = {row.get('attemptId'): row for row in events if isinstance(row, dict)
                and row.get('kind') == 'finish'}
    started = base['counts']['started']
    checkout_ms, confirm_ms = [], []
    unknown_confirm = 0
    for row in attempts:
        raw = finishes.get(row['attemptId'], {})
        checkout_end = raw.get('checkoutEndMs')
        row['checkoutEndMs'] = checkout_end
        if isinstance(checkout_end, int) and checkout_end >= row['startMs']:
            checkout_ms.append(checkout_end - row['startMs'])
        elif row.get('checkoutStatus') == 201:
            validity.append(f"{row['attemptId']}: checkout end time missing/invalid")
        if row.get('confirmStatus') is None or row.get('confirmStatus') == 0:
            unknown_confirm += bool(row.get('confirmStartMs'))
        if isinstance(row.get('confirmStartMs'), int) and isinstance(row.get('confirmEndMs'), int):
            confirm_ms.append(row['confirmEndMs'] - row['confirmStartMs'])
    if unknown_confirm:
        validity.append(f'confirm acceptance unknown: {unknown_confirm}')

    finals, observer_count = ({}, {'submitted': None, 'finished': None, 'errors': None})
    if mode == 'user-quality':
        finals, observer_count = _observations(observations, observer_stats, attempts,
                                               run_id, validity, evidence_issues)
    correctness_failures = []
    business_failures = 0
    if after is not None:
        durable = {row.get('orderId'): row.get('status') for row in after.get('paymentEvents',[])}
        for attempt in attempts:
            status = durable.get(attempt.get('orderId'))
            if status in ('FAILED','EXPIRED','QUARANTINED'):
                business_failures += 1
            final = finals.get(attempt['attemptId'],{})
            observed = final.get('outcome')
            if (observed in ('DONE','FAILED','QUARANTINED')
                    and final.get('orderId') == attempt.get('orderId')):
                attempt['injectionOutcome'] = attempt['outcome']
                attempt['outcome'] = observed
                if status in ('DONE','FAILED','EXPIRED','QUARANTINED') and observed != status:
                    correctness_failures.append(f"observed {observed} conflicts with durable {status}: {attempt.get('orderId')}")
    else:
        # Preliminary reconciliation already receives the real observed state.
        for attempt in attempts:
            final = finals.get(attempt['attemptId'],{})
            if final.get('outcome') in ('DONE','FAILED','QUARANTINED') and final.get('orderId') == attempt.get('orderId'):
                attempt['injectionOutcome'] = attempt['outcome']
                attempt['outcome'] = final['outcome']
    completion_ms = []
    if mode == 'user-quality':
        for row in attempts:
            final = finals.get(row['attemptId'])
            if not final:
                continue
            if final.get('orderId') != row.get('orderId'):
                validity.append(f"{row['attemptId']}: observation orderId mismatch")
                continue
            finish_at = final.get('atMs')
            confirm_start = row.get('confirmStartMs')
            confirm_end = row.get('confirmEndMs')
            deadline = final.get('deadlineMs')
            if (final.get('outcome') == 'DONE' and final.get('confirmAccepted') is True
                    and row.get('confirmStatus') == 202 and isinstance(finish_at, int)
                    and isinstance(confirm_start, int) and isinstance(confirm_end, int)
                    and isinstance(deadline, int) and deadline == confirm_end + 31_000
                    and confirm_start <= finish_at <= deadline):
                completion_ms.append(finish_at - confirm_start)
    checkout_p95 = _percentile(checkout_ms, 95, started)
    confirm_p95 = _percentile(confirm_ms, 95, started)
    completion_p95 = _percentile(completion_ms, 95, started) if mode == 'user-quality' else None
    completion_p99 = _percentile(completion_ms, 99, started) if mode == 'user-quality' else None
    checkout_goal = 'PASS' if checkout_p95 is not None and checkout_p95 <= 500 else 'FAIL'
    confirm_goal = 'PASS' if confirm_p95 is not None and confirm_p95 <= 500 else 'FAIL'
    completion_goal = ('NOT_MEASURED' if mode == 'capacity' else
                       'PASS' if completion_p95 is not None
                       and completion_p95 <= 3000 and completion_p99 is not None
                       and completion_p99 <= 5000 else 'FAIL')
    api_errors = base['counts']['apiErrorAttempts'] + (observer_count.get('errors') or 0)
    quality = {'checkoutP95Ms': checkout_p95, 'confirmP95Ms': confirm_p95,
               'completionP95Ms': completion_p95, 'completionP99Ms': completion_p99,
               'completionDenominator': started,
               'completionObserved': len(completion_ms) if mode == 'user-quality' else None,
               'completionUnobserved': started - len(completion_ms) if mode == 'user-quality' else None,
               'apiErrorAttempts': base['counts']['apiErrorAttempts'],
               'observerErrors': observer_count.get('errors'), 'businessFailures': business_failures,
               'goals': {'checkoutResponse': checkout_goal, 'confirmResponse': confirm_goal,
                         'userCompletion': completion_goal,
                         'apiErrors': 'PASS' if api_errors == 0 else 'FAIL'},
               'timeBoundary': 'checkout start→response; confirm start→acceptance response; '
                               'user completion from confirm start to same-order observation finish'}

    t0, t1 = base['measurementStartMs'], base['measurementEndMs']
    if t0 is None or t1 is None:
        evidence_issues.append('measurement start/end time unavailable')
        capacity = {'status': 'NOT_MEASURED', 'doneDuringLoad': None,
                    'doneAfterLoad': None, 'drainTimeMs': None}
        stability = {'status': 'INSUFFICIENT'}
    else:
        capacity = _cohort_done(before, after, attempts, t0, t1, evidence_issues)
        stability = _stability(trend_samples, t0, t1, evidence_issues)
    if not settled or settled.get('verdict') != 'PASS':
        capacity['settlementObservedDrainTimeMs'] = None
    capacity['recordedDoneRatePerSecond'] = (capacity['doneDuringLoad'] / duration_seconds
        if capacity.get('doneDuringLoad') is not None else None)
    capacity['stability'] = stability
    capacity['actualArrivalRatePerSecond'] = started / duration_seconds
    capacity['plannedArrivalRatePerSecond'] = rate
    capacity['observer'] = observer_count if mode == 'user-quality' else {'status': 'NOT_MEASURED'}
    if settled is None:
        evidence_issues.append('missing final settlement verdict')
    elif settled.get('verdict') == 'INCONCLUSIVE':
        evidence_issues.append('final settlement inconclusive')
    elif settled.get('verdict') not in ('PASS', 'FAIL'):
        evidence_issues.append('invalid final settlement verdict')
    if capacity.get('status') == 'INCOMPLETE' and settled and settled.get('verdict') == 'PASS':
        evidence_issues.append('settlement PASS but not all attempt orders have DONE histories')

    validity = _issue_unique(validity)
    arrival['status']='INCONCLUSIVE' if validity else 'VALID'
    evidence_issues = _issue_unique(evidence_issues)
    if correctness_failures or business_failures or (settled and settled.get('verdict') == 'FAIL'):
        classification, verdict = 'FAIL', 'FAIL'
    elif validity or evidence_issues:
        classification, verdict = 'INCONCLUSIVE', 'INCONCLUSIVE'
    elif api_errors:
        classification, verdict = 'FAIL', 'FAIL'
    elif stability['status'] == 'STABLE' and capacity['status'] == 'ALL_DONE':
        if mode == 'capacity':
            classification = 'SUSTAINED'
            verdict = 'PASS' if checkout_goal == confirm_goal == 'PASS' else 'FAIL'
        elif checkout_goal == confirm_goal == completion_goal == 'PASS':
            classification, verdict = 'QUALITY_MET', 'PASS'
        else:
            classification, verdict = 'DELAYED_SUSTAINED', 'FAIL'
    elif stability['status'] == 'GROWING' and capacity['status'] == 'ALL_DONE' and (capacity['doneAfterLoad'] or stability.get('stockPendingFirstLast',[0,0])[-1] > 0):
        classification, verdict = 'BURST_ABSORBED', 'FAIL'
    else:
        classification, verdict = 'INCONCLUSIVE', 'INCONCLUSIVE'

    return {**{key: base[key] for key in ('runId', 'rate', 'durationSeconds',
                                         'measurementStartMs', 'measurementEndMs',
                                         'actualFirstStartMs', 'actualLastStartMs',
                                         'productIdBase', 'attempts')},
            'counts': {key: value for key, value in base['counts'].items()
                       if key not in ('fastDone', 'eventualDone')},
            'schemaVersion': 2, 'mode': mode, 'phase':phase, 'validityIssues': validity,
            'evidenceIssues': evidence_issues, 'quality': quality,
            'correctnessFailures': correctness_failures,
            'capacity': capacity, 'arrival':arrival, 'policy': POLICY, 'classification': classification,
            'verdict': verdict}
