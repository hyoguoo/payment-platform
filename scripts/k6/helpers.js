import http from 'k6/http';
import { sleep } from 'k6';

// 공통 상수
export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
export const ORDER_POOL_SIZE = 1000;
export const POLL_INTERVAL_S = 0.5;  // sleep()는 초 단위 — 500ms
export const POLL_TIMEOUT_MS = 30000; // 30초
export const FAKE_PAYMENT_KEY = 'benchmark-payment-key';

// 공통 stages (세 스크립트에서 동일하게 import)
export const BENCHMARK_STAGES = [
  { duration: '60s', target: 50 },
  { duration: '60s', target: 100 },
  { duration: '60s', target: 200 },
];

// setup() — 측정 트래픽과 분리된 사전 데이터 준비
export function setup() {
  const orderIds = [];
  for (let i = 0; i < ORDER_POOL_SIZE; i++) {
    const res = http.post(
      `${BASE_URL}/api/v1/payments/checkout`,
      JSON.stringify({ userId: 1, orderedProductList: [{ productId: 1, quantity: 1 }] }),
      { headers: { 'Content-Type': 'application/json' } }
    );
    if (res.status === 200) {
      orderIds.push(JSON.parse(res.body).data.orderId);
    }
  }
  return { orderIds };
}

// getOrderIndex() — VU별 중복 없는 orderId 인덱스 계산
export function getOrderIndex(poolSize) {
  // __VU 1-based, __ITER 0-based
  // 단순 패턴: (VU번호 * 소수 + ITER) % 풀크기 로 충돌 최소화
  return ((__VU - 1) * 97 + __ITER) % poolSize;
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
