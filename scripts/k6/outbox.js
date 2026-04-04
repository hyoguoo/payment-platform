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
  TREND_CONFIRM_LATENCY,
  TREND_CHECKOUT_LATENCY,
  RAMPING_ARRIVAL_RATE_STAGES,
  SCENARIO_DURATION_S,
  COUNTER_CONFIRM_REQUESTS,
  COUNTER_E2E_TIMEOUT,
} from './helpers.js';

const e2eCompletion   = new Trend(TREND_E2E_COMPLETION, true);
const confirmLatency  = new Trend(TREND_CONFIRM_LATENCY, true);
const checkoutLatency = new Trend(TREND_CHECKOUT_LATENCY, true);
const confirmRequests = new Counter(COUNTER_CONFIRM_REQUESTS);
const e2eTimeoutCount = new Counter(COUNTER_E2E_TIMEOUT);

export const options = {
  scenarios: {
    // TPS 측정: HTTP 202 응답률 (채널 큐 write 완료 시점)
    throughput: {
      executor: 'ramping-arrival-rate',
      stages: RAMPING_ARRIVAL_RATE_STAGES,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
      exec: 'throughputScenario',
    },
    // E2E 지연 측정: confirm 요청 ~ DONE 상태 확인까지 (실제 결제 완료 시간)
    e2e_under_load: {
      executor: 'constant-vus',
      vus: 10,
      duration: `${SCENARIO_DURATION_S}s`,
      exec: 'e2eUnderLoadScenario',
    },
  },
};

export const handleSummary = makeSummaryHandler(__ENV.CASE_NAME || 'outbox');

export function throughputScenario() {
  const checkoutStart = Date.now();
  const orderId = checkout();
  checkoutLatency.add(Date.now() - checkoutStart);
  if (!orderId) return;

  const confirmStart = Date.now();
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
  confirmLatency.add(Date.now() - confirmStart);

  confirmRequests.add(1);
  check(confirmRes, { 'confirm accepted (202)': (r) => r.status === 202 });
}

// confirm → DONE 폴링까지 측정 — 부하 중 사용자 관점 결제 완료 시간
export function e2eUnderLoadScenario() {
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

  check(confirmRes, { 'confirm accepted (202)': (r) => r.status === 202 });

  if (confirmRes.status === 202) {
    const finalStatus = pollStatus(orderId);
    e2eCompletion.add(Date.now() - start);
    if (finalStatus === 'TIMEOUT') {
      e2eTimeoutCount.add(1);
      check(finalStatus, { 'completed under load (DONE)': () => false });
    } else {
      check(finalStatus, { 'completed under load (DONE)': (s) => s === 'DONE' });
    }
  }
}
