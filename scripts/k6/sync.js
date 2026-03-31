import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import {
  checkout,
  BASE_URL,
  PRE_ALLOCATED_VUS,
  MAX_VUS,
  FAKE_PAYMENT_KEY,
  makeSummaryHandler,
  TREND_E2E_COMPLETION,
  RAMPING_ARRIVAL_RATE_STAGES,
  SCENARIO_DURATION_S,
  COUNTER_CONFIRM_REQUESTS,
} from './helpers.js';

const e2eCompletion = new Trend(TREND_E2E_COMPLETION, true);
const confirmRequests = new Counter(COUNTER_CONFIRM_REQUESTS);

export const options = {
  scenarios: {
    // TPS 측정: ramping-arrival-rate로 부하 단계적 상승
    throughput: {
      executor: 'ramping-arrival-rate',
      stages: RAMPING_ARRIVAL_RATE_STAGES,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
      exec: 'throughputScenario',
    },
    // E2E 지연 측정: 부하 중 실제 사용자 관점의 결제 완료 시간
    // sync는 HTTP 응답 자체가 E2E 완료 시점
    e2e_under_load: {
      executor: 'constant-vus',
      vus: 10,
      duration: `${SCENARIO_DURATION_S}s`,
      exec: 'e2eUnderLoadScenario',
    },
  },
};

export const handleSummary = makeSummaryHandler(__ENV.CASE_NAME || 'sync');

export function throughputScenario() {
  const orderId = checkout();
  if (!orderId) return;

  const res = http.post(
    `${BASE_URL}/api/v1/payments/confirm`,
    JSON.stringify({
      userId: 1,
      orderId: orderId,
      amount: 50000,
      paymentKey: FAKE_PAYMENT_KEY,
    }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  confirmRequests.add(1);
  check(res, { 'status is 200': (r) => r.status === 200 });
}

// sync는 HTTP 응답 = 결제 완료이므로 응답 시간 자체가 E2E latency
export function e2eUnderLoadScenario() {
  const orderId = checkout();
  if (!orderId) return;
  const start = Date.now();

  const res = http.post(
    `${BASE_URL}/api/v1/payments/confirm`,
    JSON.stringify({
      userId: 1,
      orderId: orderId,
      amount: 50000,
      paymentKey: FAKE_PAYMENT_KEY,
    }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  confirmRequests.add(1);
  e2eCompletion.add(Date.now() - start);
  check(res, { 'completed (200)': (r) => r.status === 200 });
}
