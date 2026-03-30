import http from 'k6/http';
import { sleep } from 'k6';

// 공통 상수
export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';

// e2e Trend 메트릭 이름 상수 (sync/outbox 공통 — confirm 요청 ~ 결제 완료까지)
export const TREND_E2E_COMPLETION = 'e2e_completion_ms';
export const POLL_INTERVAL_S = 0.1;  // sleep()는 초 단위 — 100ms
export const POLL_TIMEOUT_MS = 30000; // 30초
export const FAKE_PAYMENT_KEY = 'tviva20240929050058zeWv3';

// Counter 메트릭 이름 상수
export const COUNTER_CONFIRM_REQUESTS = 'confirm_requests'; // polling 제외 순수 confirm TPS 측정용
export const COUNTER_E2E_TIMEOUT = 'e2e_timeout_count';    // pollStatus TIMEOUT 발생 횟수

// ramping-arrival-rate executor용 공통 옵션 상수 (sync/outbox 공유)
export const RAMPING_ARRIVAL_RATE_STAGES = [
  { target: 100, duration: '20s' },
  { target: 300, duration: '20s' },
  { target: 600, duration: '20s' },
];
export const PRE_ALLOCATED_VUS = 200;
export const MAX_VUS = 1500;

// checkout() — 매 iteration마다 새 orderId 생성 (orderId 재사용으로 인한 DUPLICATE KEY 원천 차단)
// Idempotency-Key: VU번호+iteration번호 조합으로 고유키 생성 → 서버 멱등 캐시 우회
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

// makeSummaryHandler() — 전략별 결과 JSON 저장용 handleSummary 생성
export function makeSummaryHandler(strategy) {
  return function handleSummary(data) {
    return {
      [`/scripts/results/${strategy}.json`]: JSON.stringify(data, null, 2),
    };
  };
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
