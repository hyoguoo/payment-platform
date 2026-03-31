import http from 'k6/http';
import { sleep } from 'k6';

// 공통 상수
export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';

// 메트릭 이름 상수
export const TREND_E2E_COMPLETION = 'e2e_completion_ms';  // confirm 요청 ~ 결제 완료까지
export const COUNTER_CONFIRM_REQUESTS = 'confirm_requests';
export const COUNTER_E2E_TIMEOUT = 'e2e_timeout_count';

export const FAKE_PAYMENT_KEY = 'tviva20240929050058zeWv3';
export const POLL_INTERVAL_S = 0.1;   // 100ms
export const POLL_TIMEOUT_MS = 30000; // 30s

// 부하 단계 — 고/저지연 동일 적용
// Tomcat PT max=200, 고지연 avg=1.15s 기준 포화점: 200/1.15 ≈ 174 req/s
// 400 req/s는 sync 포화점의 2.3배 → sync 붕괴, async 안정 구간이 뚜렷하게 나타남
export const RAMPING_ARRIVAL_RATE_STAGES = [
  { target: 100, duration: '20s' },  // warm-up (둘 다 안정)
  { target: 400, duration: '30s' },  // sync 임계점 돌파
  { target: 400, duration: '90s' },  // steady-state (차이 극명)
];
export const SCENARIO_DURATION_S = 140; // 위 stages 합산 (20+30+90)

export const PRE_ALLOCATED_VUS = 200;
export const MAX_VUS = 1500;

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
