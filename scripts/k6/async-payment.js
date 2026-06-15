/**
 * 비동기 결제 경로 e2e 부하 시나리오
 *
 * 측정 플로우: 주문 생성(checkout) → 승인 접수(confirm 202) → 상태 폴링(DONE/FAILED)
 *
 * 오염 차단 (domain_risk 핵심):
 *   F1 멱등키 충돌 — 매 iteration 고유 Idempotency-Key 사용. checkout status==201 check로 가드.
 *   F2 재고 고갈  — confirm 400(재고 부족) 미발생 check. 실행 전 bench-seed-stock.sh 필수.
 *   F3 QUARANTINED 폴링 맹점 — baseline failRate=0(compose 기본값). 폴링은 DONE/FAILED만 종결.
 *
 * 실행 전 필수 조건:
 *   1. docker-compose.benchmark.yml 스택 기동
 *   2. bench-seed-stock.sh 실행(대용량 재고 시드)
 *   3. 환경 변수 CASE_NAME 설정(결과 JSON 파일명 결정)
 *
 * 예시:
 *   k6 run \
 *     -e BASE_URL=http://localhost:8080 \
 *     -e CASE_NAME=async-low \
 *     scripts/k6/async-payment.js
 */

import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

import {
    RAMPING_ARRIVAL_RATE_STAGES,
    e2eCompletionMs,
    e2eTimeout,
    confirmRequests,
    checkoutDuplicate,
    confirmRejected,
    doCheckout,
    doConfirm,
    pollStatus,
} from './helpers.js';

// ---------------------------------------------------------------------------
// 시나리오 옵션
// ---------------------------------------------------------------------------

export const options = {
    scenarios: {
        /**
         * ramping-arrival-rate: 도착률(req/s) 기반 부하 모델.
         * VU 수와 독립적으로 초당 요청 수를 제어해 목표 TPS를 정밀하게 측정한다.
         * preAllocatedVUs는 피크 target(400 req/s)에서 처리 여유를 갖도록 설정한다.
         */
        async_payment: {
            executor: 'ramping-arrival-rate',
            startRate: 0,
            timeUnit: '1s',
            preAllocatedVUs: parseInt(__ENV.PRE_VUS || '200', 10),
            maxVUs: parseInt(__ENV.MAX_VUS || '600', 10),
            stages: RAMPING_ARRIVAL_RATE_STAGES,
        },
    },

    thresholds: {
        /**
         * confirm 응답 시간: 동기 재고 차감 + TX 후 202 반환이므로
         * p95/p99 기준으로 서버 처리 성능을 측정한다.
         */
        'http_req_duration{step:confirm}': [
            'p(95)<3000',
            'p(99)<5000',
        ],

        /**
         * e2e 완료 시간: checkout 요청 ~ pollStatus DONE/FAILED 수신까지.
         * 비동기 처리 특성상 outbox worker 폴백 주기(2s) 이상이 소요될 수 있다.
         */
        'e2e_completion_ms': [
            'p(95)<15000',
            'p(99)<30000',
        ],

        /**
         * check 성공률: checkout 201 / confirm 202 / confirm 400 미발생 모두 포함.
         * 오염 차단 check가 실패하면 측정 결과가 왜곡된 것으로 판단한다.
         */
        'checks': ['rate>0.99'],

        /**
         * e2e 타임아웃 상한: POLL_TIMEOUT_MS 초과로 종결되지 않은 요청 수.
         * baseline(failRate=0)에서는 타임아웃이 거의 발생하지 않아야 한다.
         * 전체 iteration 대비 1% 이하를 허용 상한으로 설정한다.
         */
        'e2e_timeout': ['count<100'],
    },
};

// ---------------------------------------------------------------------------
// 내부 카운터 (handleSummary JSON 출력용)
// ---------------------------------------------------------------------------

/** FAILED 상태로 종결된 건수 (PG 거절 등 정상 실패 포함) */
const paymentFailed = new Counter('payment_failed');

// ---------------------------------------------------------------------------
// VU 함수 (기본 시나리오 엔트리포인트)
// ---------------------------------------------------------------------------

/**
 * 단일 e2e 결제 플로우를 수행한다.
 *
 * 1. doCheckout — 고유 멱등키로 주문 생성(201 신규)
 * 2. checkout status==201 check — 중복 200 발생 시 오염 감지
 * 3. doConfirm — 승인 접수(202), confirm_requests 카운터 내부 증가
 * 4. confirm 202 check + confirm 400 미발생 check
 * 5. confirm 202 시각 기록 → pollStatus — DONE/FAILED 종결 대기
 * 6. DONE: e2e_completion_ms 기록 / FAILED: paymentFailed 집계 / null: 타임아웃(e2eTimeout 내부 증가)
 */
export default function () {
    // Step 1: 주문 생성
    const checkoutResponse = doCheckout();

    // Step 2: checkout 201 check (F1 멱등키 충돌 오염 차단)
    const checkoutOk = check(checkoutResponse, {
        'checkout status==201 (중복 200 아님)': (r) => r.status === 201,
    });

    if (!checkoutOk) {
        // 중복 200이거나 서버 오류 — 이 iteration은 측정 제외
        return;
    }

    const checkoutBody = parseResponseBody(checkoutResponse.body);
    if (checkoutBody === null) {
        return;
    }

    const orderId = checkoutBody.orderId;
    // checkout 응답에서 totalAmount를 추출해 confirm에 전달한다.
    const amount = checkoutBody.totalAmount
        ? String(checkoutBody.totalAmount)
        : null;

    if (!orderId || !amount) {
        return;
    }

    // Step 3: confirm 202 시각 기록 기준점 (e2e_completion_ms 시작)
    const confirmStartMs = Date.now();

    // Step 4: 승인 접수
    const confirmResponse = doConfirm(orderId, amount);

    // Step 5: confirm check — 202 확인 + F2 재고 부족 400 미발생 확인
    const confirmOk = check(confirmResponse, {
        'confirm status==202': (r) => r.status === 202,
        'confirm 재고부족 400 미발생 (F2 재고 고갈 차단)': (r) => r.status !== 400,
    });

    if (!confirmOk) {
        // confirm 실패(재고 부족 또는 서버 오류) — 폴링 불필요
        return;
    }

    // Step 6: 상태 폴링 — DONE/FAILED 종결 대기 (F3: baseline failRate=0, 폴링은 DONE/FAILED만 종결)
    const result = pollStatus(orderId);

    if (result === null) {
        // pollStatus 내부에서 e2eTimeout.add(1) 처리됨
        return;
    }

    // e2e 완료 시간 기록 (confirm 202 시각부터 폴링 종결까지)
    const e2eDurationMs = Date.now() - confirmStartMs;
    e2eCompletionMs.add(e2eDurationMs);

    if (result.status === 'FAILED') {
        // FAILED는 정상 종결(PG 거절 등), 별도 집계만 수행
        paymentFailed.add(1);
    }
}

// ---------------------------------------------------------------------------
// 결과 요약 핸들러
// ---------------------------------------------------------------------------

/**
 * 측정 종료 후 결과를 JSON 파일로 출력한다.
 *
 * CASE_NAME 환경 변수로 파일명을 결정한다(예: async-low, async-high).
 * run-benchmark.sh에서 CASE_NAME을 주입하며, 미설정 시 'default'를 사용한다.
 *
 * @param {object} data k6 요약 데이터
 * @returns {object} 출력 대상(파일 경로 → 내용) 맵
 */
export function handleSummary(data) {
    const caseName = __ENV.CASE_NAME || 'default';
    const outputPath = `results/${caseName}.json`;

    const summary = {
        caseName: caseName,
        timestamp: new Date().toISOString(),
        thresholds: extractThresholds(data),
        metrics: extractMetrics(data),
    };

    return {
        [outputPath]: JSON.stringify(summary, null, 2),
        stdout: textSummary(data, { indent: ' ', enableColors: true }),
    };
}

// ---------------------------------------------------------------------------
// 내부 유틸리티
// ---------------------------------------------------------------------------

/**
 * HTTP 응답 본문을 JSON으로 파싱한다. 실패 시 null을 반환한다.
 *
 * @param {string} body HTTP 응답 본문 문자열
 * @returns {object|null} 파싱된 객체 또는 null
 */
function parseResponseBody(body) {
    try {
        const parsed = JSON.parse(body);
        // 공통 응답 래퍼 {data:...} 대응 — data 키가 있으면 벗기고, 없으면 원본 반환
        return parsed && parsed.data !== undefined ? parsed.data : parsed;
    } catch (_) {
        return null;
    }
}

/**
 * 요약 데이터에서 threshold 결과를 추출한다.
 *
 * @param {object} data k6 요약 데이터
 * @returns {object} threshold 이름 → pass 여부 맵
 */
function extractThresholds(data) {
    const result = {};
    if (!data.thresholds) {
        return result;
    }
    const thresholdNames = Object.keys(data.thresholds);
    for (let i = 0; i < thresholdNames.length; i++) {
        const name = thresholdNames[i];
        result[name] = data.thresholds[name].ok;
    }
    return result;
}

/**
 * 요약 데이터에서 핵심 지표를 추출한다.
 *
 * @param {object} data k6 요약 데이터
 * @returns {object} 지표 이름 → 값 맵
 */
function extractMetrics(data) {
    const metrics = data.metrics || {};

    return {
        http_req_duration_confirm: extractTrendStats(metrics['http_req_duration{step:confirm}']),
        e2e_completion_ms: extractTrendStats(metrics['e2e_completion_ms']),
        checks_rate: extractRateValue(metrics['checks']),
        e2e_timeout_count: extractCounterValue(metrics['e2e_timeout']),
        confirm_requests_count: extractCounterValue(metrics['confirm_requests']),
        checkout_duplicate_count: extractCounterValue(metrics['checkout_duplicate']),
        confirm_rejected_count: extractCounterValue(metrics['confirm_rejected']),
        payment_failed_count: extractCounterValue(metrics['payment_failed']),
    };
}

/**
 * Trend 메트릭에서 p95/p99/avg/min/max를 추출한다.
 *
 * @param {object|undefined} metric k6 Trend 메트릭 객체
 * @returns {object|null} 통계 값 맵 또는 null
 */
function extractTrendStats(metric) {
    if (!metric || !metric.values) {
        return null;
    }
    const v = metric.values;
    return {
        p95: v['p(95)'],
        p99: v['p(99)'],
        avg: v['avg'],
        min: v['min'],
        max: v['max'],
    };
}

/**
 * Rate 메트릭에서 비율 값을 추출한다.
 *
 * @param {object|undefined} metric k6 Rate 메트릭 객체
 * @returns {number|null} 비율(0~1) 또는 null
 */
function extractRateValue(metric) {
    if (!metric || !metric.values) {
        return null;
    }
    return metric.values.rate;
}

/**
 * Counter 메트릭에서 카운트 값을 추출한다.
 *
 * @param {object|undefined} metric k6 Counter 메트릭 객체
 * @returns {number|null} 카운트 또는 null
 */
function extractCounterValue(metric) {
    if (!metric || !metric.values) {
        return null;
    }
    return metric.values.count;
}
