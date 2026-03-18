import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { checkout, BASE_URL, BENCHMARK_STAGES, FAKE_PAYMENT_KEY, pollStatus } from './helpers.js';

const e2eLatency = new Trend('e2e_latency_ms', true);

export const options = {
  stages: BENCHMARK_STAGES,
};

// Outbox 전략: POST /confirm → 202 Accepted → 폴링 → DONE
export default function () {
  const start = Date.now();
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

  check(confirmRes, { 'confirm accepted (202)': (r) => r.status === 202 });

  if (confirmRes.status === 202) {
    const finalStatus = pollStatus(orderId);
    e2eLatency.add(Date.now() - start);
    check(finalStatus, { 'completed (DONE)': (s) => s === 'DONE' });
  }
}
