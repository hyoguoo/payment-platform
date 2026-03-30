import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import {
  checkout,
  BASE_URL,
  PRE_ALLOCATED_VUS,
  MAX_VUS,
  FAKE_PAYMENT_KEY,
  pollStatus,
  makeSummaryHandler,
  TREND_E2E_COMPLETION,
  RAMPING_ARRIVAL_RATE_STAGES,
  COUNTER_CONFIRM_REQUESTS,
  COUNTER_E2E_TIMEOUT,
} from './helpers.js';

const e2eCompletion = new Trend(TREND_E2E_COMPLETION, true);
const confirmRequests = new Counter(COUNTER_CONFIRM_REQUESTS);
const e2eTimeoutCount = new Counter(COUNTER_E2E_TIMEOUT);

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

export const handleSummary = makeSummaryHandler(__ENV.CASE_NAME || 'outbox');

// measureE2e — checkout → POST /confirm → pollStatus → e2eCompletion 기록
// TIMEOUT 발생 시 e2eTimeoutCount 증가 + check fail 처리
function measureE2e(checkLabel) {
  const orderId = checkout();
  if (!orderId) return;
  const start = Date.now();

  const confirmRes = http.post(
    `${BASE_URL}/api/v1/payments/confirm`,
    JSON.stringify({
      userId: 1,
      orderId: orderId,
      amount: 50000,
      paymentKey: FAKE_PAYMENT_KEY,
    }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  if (confirmRes.status === 202) {
    const finalStatus = pollStatus(orderId);
    e2eCompletion.add(Date.now() - start);
    if (finalStatus === 'TIMEOUT') {
      e2eTimeoutCount.add(1);
      check(finalStatus, { [checkLabel]: () => false });
    } else {
      check(finalStatus, { [checkLabel]: (s) => s === 'DONE' });
    }
  }
}

// throughputScenario — checkout → POST /confirm → 202 check (폴링 없음)
export function throughputScenario() {
  const orderId = checkout();
  if (!orderId) return;

  const confirmRes = http.post(
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
  check(confirmRes, { 'confirm accepted (202)': (r) => r.status === 202 });
}

// e2eUnderLoadScenario — 부하 중 e2e latency 측정
export function e2eUnderLoadScenario() {
  measureE2e('completed under load (DONE)');
}
