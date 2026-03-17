import http from 'k6/http';
import { check } from 'k6';
import { checkout, BASE_URL, BENCHMARK_STAGES, FAKE_PAYMENT_KEY } from './helpers.js';

export const options = {
  stages: BENCHMARK_STAGES,
};

// Sync 전략: POST /confirm → 200 OK 기대
export default function () {
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

  check(res, {
    'status is 200': (r) => r.status === 200,
  });
}
