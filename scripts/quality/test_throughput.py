import unittest
from datetime import datetime, timezone

from throughput import analyze
from capacity import JOBS


T0 = 1_000_000


def stamp(epoch_ms):
    return datetime.fromtimestamp(epoch_ms / 1000, timezone.utc).isoformat()


def fixture(*, rate=1, duration=120, starts=None, done_latency=1200,
            observed='DONE', observer_status=202, dropped=0, workload='user-quality'):
    starts = rate * duration if starts is None else starts
    events, observations, orders = [], [], []
    for index in range(starts):
        start = T0 + int(index * 1000 / rate)
        aid, oid = f'r-{index}', f'order-{index}'
        common = {'runId': 'r', 'attemptId': aid, 'idempotencyKey': aid,
                  'productId': 1000 + index % 100, 'startMs': start,
                  'scenarioStartMs': T0, 'mode':workload, 'phase':'measurement'}
        events.append(dict(common, kind='start'))
        events.append(dict(common, kind='finish', orderId=oid, checkoutEndMs=start + 50,
                           checkoutStatus=201, confirmStartMs=start + 60,
                           confirmEndMs=start + 110, confirmStatus=observer_status,
                           doneMs=None, finishedMs=start + 120, outcome='ACCEPTED',
                           apiErrors=[], pollCount=0))
        observations.append({'kind': 'poll_start', 'attemptId': aid,
                             'orderId': oid, 'pollNo': 1, 'atMs': start + 1110})
        observations.append({'kind': 'poll_result', 'attemptId': aid,
                             'orderId': oid, 'pollNo': 1, 'requestStartMs': start + 1110,
                             'atMs': start + 60 + done_latency,
                             'httpStatus': 200, 'observedStatus': 'DONE',
                             'late': False, 'error': None})
        observations.append({'runId': 'r', 'kind': 'observation_finish',
                             'attemptId': aid, 'orderId': oid,
                             'confirmAccepted': observer_status == 202,
                             'confirmStartMs': start+60, 'confirmEndMs': start+110, 'confirmStatus': observer_status,
                             'deadlineMs': start + 31_110,
                             'atMs': start + 60 + done_latency,
                             'outcome': observed, 'pollCount': 1})
        orders.append(oid)
    summary = {'metrics': {'iterations': {'values': {'count': starts}},
                           'dropped_iterations': {'values': {'count': dropped}}}}
    before = {'paymentEvents': []}
    after = {'paymentEvents': [{'orderId': oid, 'status': 'DONE'} for oid in orders],
             'paymentHistories': [{'orderId': oid, 'currentStatus': 'DONE',
                                   'changeStatusAt': stamp(T0 + int(index * 1000 / rate) + 1000)}
                                  for index, oid in enumerate(orders)]}
    stats = {'submitted': starts, 'finished': starts, 'capacitySkipped': 0,
             'lateSkipped': 0, 'errors': 0, 'rejected': 0, 'unobservable': 0,
             'feedErrors': [], 'incomplete': False, 'polls': starts,
             'pollIntervalMs': 1000, 'maxPolls': 30, 'deadlineOffsetMs': 31000}
    trend = []
    for sec in (60, 70, 80, 90, 100, 110, 120):
        count = min(starts, rate * sec)
        trend.append({'atMs': T0 + sec * 1000, 'captureStartMs': T0 + sec * 1000,
                      'captureEndMs': T0 + sec * 1000, 'orders': count,
                      'done': count, 'pending': 0, 'oldestPendingAgeMs': 0,
                      'memoryBytes': dict({job:1000 for job in JOBS}, **{f'container:c{i}':1000 for i in range(20)}, **{'host:swapUsedBytes':0}),
                      'memoryCoverage':{'heapJobs':sorted(JOBS),'containerCount':20,'hostSwap':True},
                      'sourceSampleAtMs':{job:T0+sec*1000-1000 for job in JOBS},
                      'stockCommitted':count,'stockPending':0,'pipelinePending':0})
    return dict(events=events, summary=summary, run_id='r', rate=rate,
                duration_seconds=duration, observations=observations,
                observer_stats=stats, before=before, after=after,
                trend_samples=trend, settled={'verdict': 'PASS', 'failures': [],
                                              'pending': [], 'unknownAcceptance': []})


class ThroughputTest(unittest.TestCase):
    def test_user_quality_pass_uses_full_denominator_and_keeps_legacy_out(self):
        args = fixture()
        report = analyze(mode='user-quality', **args)
        self.assertEqual(report['schemaVersion'], 2)
        self.assertEqual(report['classification'], 'QUALITY_MET')
        self.assertEqual(report['verdict'], 'PASS')
        self.assertEqual(report['quality']['completionDenominator'], 120)
        self.assertEqual(report['quality']['completionP95Ms'], 1200)
        self.assertEqual(report['quality']['completionP99Ms'], 1200)
        self.assertNotIn('fastDoneRate', report)
        self.assertEqual(len(report['attempts']), 120)

    def test_capacity_does_not_claim_user_completion_and_needs_final_evidence(self):
        args = fixture(workload='capacity')
        args['observations'] = None
        args['observer_stats'] = None
        report = analyze(mode='capacity', **args)
        self.assertEqual(report['quality']['completionP95Ms'], None)
        self.assertEqual(report['quality']['goals']['userCompletion'], 'NOT_MEASURED')
        self.assertEqual(report['classification'], 'SUSTAINED')
        args['after'] = None
        args['settled'] = None
        unfinished = analyze(mode='capacity', **args)
        self.assertEqual(unfinished['verdict'], 'INCONCLUSIVE')
        self.assertTrue(unfinished['evidenceIssues'])
        self.assertFalse(unfinished['validityIssues'])

    def test_late_done_or_uncertain_confirm_is_never_timely_ui_done(self):
        args = fixture()
        args['observations'][2]['atMs'] = args['observations'][2]['deadlineMs'] + 1
        args['observations'][2]['outcome'] = 'TIMEOUT'
        args['observations'][1]['atMs'] = args['observations'][2]['atMs']
        args['observations'][1]['late'] = True
        report = analyze(mode='user-quality', **args)
        self.assertEqual(report['quality']['completionObserved'], 119)
        self.assertEqual(report['quality']['completionUnobserved'], 1)
        self.assertEqual(report['quality']['goals']['userCompletion'], 'PASS')
        args['observations'][5]['atMs'] = args['observations'][5]['deadlineMs'] + 1
        args['observations'][5]['outcome'] = 'TIMEOUT'
        args['observations'][4]['atMs'] = args['observations'][5]['atMs']
        args['observations'][4]['late'] = True
        report = analyze(mode='user-quality', **args)
        self.assertEqual(report['quality']['completionObserved'], 118)
        self.assertEqual(report['quality']['completionP99Ms'], None)
        self.assertEqual(report['quality']['goals']['userCompletion'], 'FAIL')
        args = fixture(observer_status=0)
        report = analyze(mode='user-quality', **args)
        self.assertEqual(report['quality']['completionObserved'], 0)
        self.assertEqual(report['verdict'], 'INCONCLUSIVE')

    def test_dropped_arrivals_and_observer_saturation_are_inconclusive(self):
        args = fixture(starts=119, dropped=1)
        report = analyze(mode='user-quality', **args)
        self.assertEqual(report['arrival']['status'],'INCONCLUSIVE')
        self.assertEqual(report['verdict'], 'INCONCLUSIVE')
        self.assertEqual(report['classification'], 'INCONCLUSIVE')
        args = fixture()
        args['observer_stats']['capacitySkipped'] = 1
        report = analyze(mode='user-quality', **args)
        self.assertEqual(report['classification'], 'INCONCLUSIVE')

    def test_growing_backlog_cleared_after_load_is_burst_absorbed(self):
        args = fixture(workload='capacity')
        args['trend_samples'] = [dict(s, done=s['done'] - (index + 1) * 5,
                                      pending=(index + 1) * 5,
                                      oldestPendingAgeMs=(index + 1) * 10000,
                                      stockCommitted=s['done']-(index+1)*5,stockPending=0,pipelinePending=(index+1)*5)
                                 for index, s in enumerate(args['trend_samples'])]
        args['after']['paymentHistories'][-1]['changeStatusAt'] = stamp(T0 + 122_000)
        report = analyze(mode='capacity', **{k: v for k, v in args.items()
                                             if k not in ('observations', 'observer_stats')})
        self.assertEqual(report['classification'], 'BURST_ABSORBED')
        self.assertEqual(report['capacity']['doneAfterLoad'], 1)
        self.assertGreaterEqual(report['capacity']['drainTimeMs'], 2000)

    def test_no_memory_or_only_short_sample_window_cannot_prove_sustained(self):
        args = fixture(workload='capacity')
        args['trend_samples'][-1]['memoryBytes'] = None
        report = analyze(mode='capacity', **{k: v for k, v in args.items()
                                             if k not in ('observations', 'observer_stats')})
        self.assertEqual(report['classification'], 'INCONCLUSIVE')
        args = fixture(workload='capacity')
        args['trend_samples'] = args['trend_samples'][-2:]
        report = analyze(mode='capacity', **{k: v for k, v in args.items()
                                             if k not in ('observations', 'observer_stats')})
        self.assertEqual(report['classification'], 'INCONCLUSIVE')

    def test_real_collector_numeric_types_capture_interval_and_empty_feed_errors(self):
        args = fixture(duration=180)
        seed = args['trend_samples'][0]
        args['trend_samples'] = [dict(seed, atMs=T0+sec*1000,
            captureStartMs=T0+sec*1000, captureEndMs=T0+sec*1000,
            orders=sec, done=sec,stockCommitted=sec,sourceSampleAtMs={job:T0+sec*1000-1000 for job in JOBS}) for sec in range(90,180,10)]
        args['observer_stats']['feedErrors'] = []
        for sample in args['trend_samples']:
            sample['captureStartMs'] -= 5
            sample['captureEndMs'] += 5
            sample['oldestPendingAgeMs'] = 0.0
            sample['memoryBytes'] = {key:float(value) for key,value in sample['memoryBytes'].items()}
        report = analyze(mode='user-quality', **args)
        self.assertEqual(report['classification'], 'QUALITY_MET', report['evidenceIssues'])

    def test_delayed_but_stable_is_distinct_from_capacity_failure(self):
        report = analyze(mode='user-quality', **fixture(done_latency=6000))
        self.assertEqual(report['classification'], 'DELAYED_SUSTAINED')
        self.assertEqual(report['capacity']['stability']['status'], 'STABLE')
        self.assertEqual(report['quality']['goals']['userCompletion'], 'FAIL')

    def test_observer_error_cannot_be_hidden_by_zero_summary_counter(self):
        args = fixture()
        args['observations'][1]['error'] = 'http_503'
        report = analyze(mode='user-quality', **args)
        self.assertEqual(report['verdict'], 'INCONCLUSIVE')
        self.assertTrue(any('errors mismatch' in s for s in report['evidenceIssues']))

    def test_done_history_and_final_stock_observation_have_distinct_time_bounds(self):
        args = fixture(workload='capacity')
        args['after']['captureCompletedAtMs'] = T0 + 125000
        report = analyze(mode='capacity', **args)
        self.assertEqual(report['capacity']['recordedDoneP95Ms'], 940)
        self.assertEqual(report['capacity']['drainTimeMs'], 0)
        self.assertEqual(report['capacity']['settlementObservedDrainTimeMs'], 5000)

    def test_bounded_gc_oscillation_is_not_monotonic_memory_growth(self):
        args = fixture(workload='capacity')
        for sample, value in zip(args['trend_samples'], (1000, 1000, 2000, 1000, 1000, 1000, 2000)):
            sample['memoryBytes'] = {job: value for job in sample['memoryBytes']}
        report = analyze(mode='capacity', **args)
        self.assertEqual(report['classification'], 'SUSTAINED')
        for index, sample in enumerate(args['trend_samples']):
            sample['memoryBytes'] = {job: 1000 + index*100 for job in sample['memoryBytes']}
        report = analyze(mode='capacity', **args)
        self.assertEqual(report['classification'], 'INCONCLUSIVE')

    def test_bounded_pending_variation_is_distinct_from_growing_backlog(self):
        args = fixture(workload='capacity')
        for sample, count in zip(args['trend_samples'], (0, 0, 2, 0, 0, 0, 2)):
            sample['pending'] = count
            sample['done'] = sample['orders'] - count
            sample['oldestPendingAgeMs'] = count * 100
            sample['stockCommitted']=sample['done']
            sample['pipelinePending']=count
        report = analyze(mode='capacity', **args)
        self.assertEqual(report['classification'], 'SUSTAINED')

    def test_changed_poll_policy_or_early_poll_cannot_claim_ui_quality(self):
        for key, value in (('pollIntervalMs', 500), ('maxPolls', 60), ('deadlineOffsetMs', 60000)):
            args = fixture()
            args['observer_stats'][key] = value
            self.assertEqual(analyze(mode='user-quality', **args)['verdict'], 'INCONCLUSIVE')
        args = fixture()
        args['observations'][0]['atMs'] -= 500
        self.assertEqual(analyze(mode='user-quality', **args)['verdict'], 'INCONCLUSIVE')

    def test_ui_failed_but_durable_done_is_correctness_failure_not_percentile_tail(self):
        args=fixture()
        args['observations'][1]['observedStatus']='FAILED'
        args['observations'][2]['outcome']='FAILED'
        result=analyze(mode='user-quality',**args)
        self.assertEqual(result['verdict'],'FAIL')
        self.assertTrue(result['correctnessFailures'])
        self.assertEqual(result['attempts'][0]['outcome'],'FAILED')
        args['after']['paymentEvents'][0]['status']='FAILED'
        args['after']['paymentHistories']=args['after']['paymentHistories'][1:]
        result=analyze(mode='user-quality',**args)
        self.assertEqual(result['verdict'],'FAIL')
        self.assertEqual(result['quality']['businessFailures'],1)

    def test_on_time_terminal_poll_cannot_disappear_into_a_timeout_final(self):
        args=fixture()
        args['observations'][2]['outcome']='TIMEOUT'
        args['observations'][2]['deadlineMs']=None
        self.assertEqual(analyze(mode='user-quality',**args)['verdict'],'INCONCLUSIVE')

    def test_legacy_or_mismatched_mode_and_phase_cannot_be_relabelled(self):
        args=fixture(workload='legacy')
        self.assertEqual(analyze(mode='capacity',**args)['verdict'],'INCONCLUSIVE')
        args=fixture()
        args['events'][0]['phase']='warmup'
        self.assertEqual(analyze(mode='user-quality',**args)['verdict'],'INCONCLUSIVE')

    def test_product_stock_backlog_cannot_hide_behind_fast_payment_done(self):
        args=fixture(workload='capacity')
        for sample in args['trend_samples']:
            sample['stockCommitted']=sample['orders']//2
            sample['stockPending']=sample['done']-sample['stockCommitted']
            sample['pipelinePending']=sample['orders']-sample['stockCommitted']
        self.assertEqual(analyze(mode='capacity',**args)['classification'],'BURST_ABSORBED')
        args=fixture(workload='capacity')
        del args['trend_samples'][0]['stockCommitted']
        self.assertEqual(analyze(mode='capacity',**args)['verdict'],'INCONCLUSIVE')

    def test_container_or_original_scrape_evidence_missing_is_not_stable(self):
        for change in ('container','freshness'):
            args=fixture(workload='capacity')
            for sample in args['trend_samples']:
                if change=='container': sample['memoryBytes'].pop('container:c0')
                else: sample['sourceSampleAtMs']['payment-service']-=60000
            self.assertEqual(analyze(mode='capacity',**args)['verdict'],'INCONCLUSIVE')

    def test_small_scheduling_jitter_can_put_two_starts_after_end_without_losing_arrivals(self):
        args=fixture(rate=80,duration=1,starts=81,workload='capacity')
        for row in args['events']:
            if int(row['attemptId'].rsplit('-',1)[1]) >= 79:
                for key in ('startMs','checkoutEndMs','confirmStartMs','confirmEndMs','finishedMs'):
                    if key in row:row[key]+=13
        report=analyze(mode='capacity',**args)
        self.assertEqual(report['counts']['boundaryStarts'],2)
        self.assertFalse(any('boundary start' in x for x in report['validityIssues']))
        self.assertLessEqual(report['arrival']['maxScheduleLagMs'],13)
        import results
        legacy=results.analyze(args['events'],args['summary'],'r',80,1)
        self.assertTrue(any('boundary start' in x for x in legacy['validityIssues']))

    def test_large_scheduling_lag_and_missing_iteration_cannot_be_hidden_by_exact_count(self):
        args=fixture()
        for row in args['events']:
            for key in ('startMs','checkoutEndMs','confirmStartMs','confirmEndMs','finishedMs'):
                if key in row:row[key]+=101
        report=analyze(mode='user-quality',**args)
        self.assertEqual(report['verdict'],'INCONCLUSIVE')
        self.assertTrue(any('scheduling lag' in x for x in report['validityIssues']))
        args=fixture()
        for row in args['events'][:2]:row['attemptId']='r-9999'
        report=analyze(mode='user-quality',**args)
        self.assertTrue(any('iteration sequence' in x for x in report['validityIssues']))

    def test_settlement_failure_is_a_failure_not_capacity_success(self):
        args = fixture()
        args['settled']['verdict'] = 'FAIL'
        report = analyze(mode='user-quality', **args)
        self.assertEqual(report['verdict'], 'FAIL')
        self.assertEqual(report['classification'], 'FAIL')


if __name__ == '__main__':
    unittest.main()
