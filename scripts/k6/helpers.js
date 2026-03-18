import http from 'k6/http';
import { sleep } from 'k6';

// 공통 상수
export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
export const POLL_INTERVAL_S = 0.5;  // sleep()는 초 단위 — 500ms
export const POLL_TIMEOUT_MS = 30000; // 30초
export const FAKE_PAYMENT_KEY = 'tviva20240929050058zeWv3';

// 공통 stages (세 스크립트에서 동일하게 import)
export const BENCHMARK_STAGES = [
  { duration: '60s', target: 50 },
  { duration: '60s', target: 100 },
  { duration: '60s', target: 200 },
];

// checkout() — 매 iteration마다 새 orderId 생성 (orderId 재사용으로 인한 DUPLICATE KEY 원천 차단)
export function checkout() {
  const res = http.post(
    `${BASE_URL}/api/v1/payments/checkout`,
    JSON.stringify({ userId: 1, orderedProductList: [{ productId: 1, quantity: 1 }] }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (res.status !== 200) return null;
  return JSON.parse(res.body).data.orderId;
}

// pollStatus() — 비동기 전략의 end-to-end 완료 대기
export function pollStatus(orderId) {
  const startTime = Date.now();
  while (Date.now() - startTime < POLL_TIMEOUT_MS) {
    sleep(POLL_INTERVAL_S);
    const res = http.get(`${BASE_URL}/api/v1/payments/${orderId}/status`);
    if (res.status === 200) {
      const body = JSON.parse(res.body);
      if (body.data.status === 'DONE' || body.data.status === 'FAILED') {
        return body.data.status;
      }
    }
  }
  return 'TIMEOUT';
}
