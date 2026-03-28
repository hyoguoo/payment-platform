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
  COUNTER_CONFIRM_REQUESTS,
} from './helpers.js';

const e2eCompletion = new Trend(TREND_E2E_COMPLETION, true);
const confirmRequests = new Counter(COUNTER_CONFIRM_REQUESTS);

export const options = {
  scenarios: {
    throughput: {
      executor: 'ramping-arrival-rate',
      stages: RAMPING_ARRIVAL_RATE_STAGES,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
      exec: 'throughputScenario',
    },
    e2e_under_load: {
      executor: 'constant-vus',
      vus: 5,
      duration: '60s',
      exec: 'e2eUnderLoadScenario',
    },
  },
};

export const handleSummary = makeSummaryHandler(__ENV.CASE_NAME || 'sync');

// throughputScenario — checkout → POST /confirm → 200 check
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

// e2eUnderLoadScenario — checkout → POST /confirm → e2eLatency (동기이므로 응답이 곧 완료)
export function e2eUnderLoadScenario() {
  const start = Date.now();
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
  e2eCompletion.add(Date.now() - start);
  check(res, { 'completed (200)': (r) => r.status === 200 });
}
