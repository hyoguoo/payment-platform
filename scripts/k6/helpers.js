import http from 'k6/http';
import { sleep } from 'k6';

// 공통 상수
export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';

// 메트릭 이름 상수
export const TREND_E2E_COMPLETION   = 'e2e_completion_ms';  // confirm 요청 ~ 결제 완료까지
export const TREND_CONFIRM_LATENCY  = 'confirm_ms';          // confirm API 단독 응답 시간
export const TREND_CHECKOUT_LATENCY = 'checkout_ms';         // checkout API 단독 응답 시간
export const COUNTER_CONFIRM_REQUESTS = 'confirm_requests';
export const COUNTER_E2E_TIMEOUT = 'e2e_timeout_count';

export const FAKE_PAYMENT_KEY = 'tviva20240929050058zeWv3';
export const POLL_INTERVAL_S = 0.1;   // 100ms
export const POLL_TIMEOUT_MS = 30000; // 30s

// 부하 단계 — 고/저지연 동일 적용
// Tomcat PT max=200, 고지연 avg=2.75s 기준 포화점: 200/2.75 ≈ 72 req/s
// 100 req/s는 포화점(72)을 상당히 초과 → sync 심한 포화, async는 안정
// DB ops: 100 req/s × ~6 ops ≈ 600 ops/s → Docker MySQL 안전 범위
export const RAMPING_ARRIVAL_RATE_STAGES = [
  { target: 20,  duration: '20s' },  // warm-up
  { target: 100, duration: '30s' },  // sync 임계점 초과 가속
  { target: 100, duration: '90s' },  // steady-state
];
export const SCENARIO_DURATION_S = 140;

export const PRE_ALLOCATED_VUS = 200;
export const MAX_VUS = 1000;

// checkout() — 매 iteration마다 새 orderId 생성
export function checkout() {
  const idempotencyKey = `bench-vu${__VU}-iter${__ITER}`;
  const res = http.post(
    `${BASE_URL}/api/v1/payments/checkout`,
    JSON.stringify({ userId: 1, orderedProductList: [{ productId: 1, quantity: 1 }] }),
    { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey } }
  );
  if (res.status !== 201) return null;
  return JSON.parse(res.body).data.orderId;
}

// makeSummaryHandler() — 케이스별 결과 JSON 저장
export function makeSummaryHandler(caseName) {
  return function handleSummary(data) {
    return {
      [`/scripts/results/${caseName}.json`]: JSON.stringify(data, null, 2),
    };
  };
}

// pollStatus() — 비동기 전략의 E2E 완료 대기
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
