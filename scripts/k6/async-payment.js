/**
 * 비동기 결제 경로 e2e 부하 시나리오
 *
 * 시나리오 둘을 함께 돌린다:
 *   async_payment   — 부하 본체. checkout → confirm 접수까지만 확인하고 VU 를 곧바로 놓아준다
 *                      (SKIP_POLL=true). iteration 이 수십 밀리초로 끝나 VU 수십 개로도 초당
 *                      수백 건을 낼 수 있다 — 폴링으로 VU 를 붙잡던 옛 구조는 도착률이
 *                      VU 상한 ÷ 종결 시간에 갇혀 목표 도착률에 못 닿고 dropped_iterations 만
 *                      쌓았다. bench-scaleout-cycle.sh 는 이 플래그를 true 로 고정해서 부른다.
 *                      SKIP_POLL 미지정(false) 이면 옛 방식대로 confirm 이후 폴링까지 마친 뒤
 *                      VU 를 놓아준다 — sweep.sh 의 SKIP_POLL=false(비동기 e2e 경로) 사용은
 *                      이 자리를 그대로 쓴다.
 *   latency_sample  — SAMPLE_RATE>0 일 때만 추가되는 낮은 고정 도착률 시나리오. checkout →
 *                      confirm → 종결(DONE/FAILED) 까지 전 과정을 끝까지 지켜본다. 체감 지연은
 *                      이 시나리오의 표본만으로 잰다 — 전수 관측이 아니다. async_payment 가
 *                      폴링을 놓은 자리를 이 시나리오가 대신한다.
 *
 * 오염 차단 (domain_risk 핵심):
 *   F1 멱등키 충돌 — 매 iteration 고유 Idempotency-Key 사용. checkout status==201 check로 가드.
 *   F2 재고 고갈  — confirm 400(재고 부족) 미발생 check. 실행 전 bench-seed-stock.sh 필수.
 *   F3 QUARANTINED 폴링 맹점 — baseline failRate=0(compose 기본값). 폴링은 DONE/FAILED만 종결.
 *
 * 폴링 전략 (POLL_STRATEGY env, latency_sample 및 SKIP_POLL=false 인 async_payment 에 적용):
 *   fixed   — 고정 간격(POLL_INTERVAL_MS). 기본값. thundering herd 가능.
 *   backoff — 지수 백오프 + 완전 지터(Full Jitter). thundering herd 방지.
 *             VU별 재시도가 분산되어 서버 폴링 부하가 균등화된다.
 *
 * 계측 이원화:
 *   체감 latency  — confirm 202 수신(confirmAt) ~ 폴링 DONE 수신(resolvedAt). latency_sample 표본만.
 *   처리 latency  — payment_history 최초 DONE 전이 시각(DB). verify-settlement.sh 사후 조인 —
 *                   부하 도구가 관측했는지와 무관하게 시스템이 실제로 처리한 전수를 담는다.
 *   부하 무결성   — dropped_iterations(발사조차 못 한 반복)/iterations(실제로 발사된 반복).
 *                   0이 아니면 그 사이클은 부하 미달이라 처리율 비교에 못 쓴다(bench-scaleout-cycle.sh
 *                   가 결과에 남긴다).
 *   사후 조인 키  — orderId. console.log JSON 라인으로 confirmAt·resolvedAt·pollEvents 출력.
 *                   k6 --log-output=file=<path> 또는 stderr 리디렉션으로 추출 가능.
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
 *     -e SKIP_POLL=true \
 *     -e SAMPLE_RATE=2 \
 *     scripts/k6/async-payment.js
 *
 *   # 폴링 OFF (동기 confirm 경로 병목 측정, 표본도 없이):
 *   k6 run -e SKIP_POLL=true ...
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

// 부하 모델 분기:
//   CONSTANT_RATE>0 → constant-arrival-rate (고정 rate steady-state, 병목 sweep용)
//   미지정          → ramping-arrival-rate (baseline 곡선)
const SWEEP_RATE = parseInt(__ENV.CONSTANT_RATE || '0', 10);
const loadScenario = SWEEP_RATE > 0
    ? {
        executor: 'constant-arrival-rate',
        rate: SWEEP_RATE,
        timeUnit: '1s',
        duration: __ENV.DURATION || '30s',
        preAllocatedVUs: parseInt(__ENV.PRE_VUS || '100', 10),
        maxVUs: parseInt(__ENV.MAX_VUS || '400', 10),
    }
    : {
        executor: 'ramping-arrival-rate',
        startRate: 0,
        timeUnit: '1s',
        preAllocatedVUs: parseInt(__ENV.PRE_VUS || '200', 10),
        maxVUs: parseInt(__ENV.MAX_VUS || '600', 10),
        stages: RAMPING_ARRIVAL_RATE_STAGES,
    };

/**
 * "Ns" 형식 duration 문자열에서 초 단위 정수를 뽑는다. 단위가 없으면(순수 숫자 문자열) 그대로 쓴다.
 *
 * @param {string} durationStr k6 duration 문자열(예: "60s")
 * @returns {number} 초
 */
function parseDurationSeconds(durationStr) {
    return parseInt(String(durationStr).replace(/s$/, ''), 10);
}

/**
 * ramping-arrival-rate stages 배열의 총 소요 시간(초)을 더한다.
 *
 * @param {Array<{duration: string, target: number}>} stages
 * @returns {number} 총 초
 */
function sumStageDurationSeconds(stages) {
    return stages.reduce((total, stage) => total + parseDurationSeconds(stage.duration), 0);
}

// 지연 표본 시나리오 — 낮은 고정 도착률로 확정부터 종결까지 전 과정을 지켜본다. SAMPLE_RATE<=0
// (기본값)이면 시나리오 자체를 만들지 않는다 — 이 표본이 필요 없는 도구(sweep.sh 등)의 기존
// 실행 결과에 영향을 주지 않기 위해서다.
const SAMPLE_RATE = parseInt(__ENV.SAMPLE_RATE || '0', 10);

const scenarios = { async_payment: loadScenario };
if (SAMPLE_RATE > 0) {
    const sampleDurationSec = SWEEP_RATE > 0
        ? parseDurationSeconds(__ENV.DURATION || '30s')
        : sumStageDurationSeconds(RAMPING_ARRIVAL_RATE_STAGES);
    scenarios.latency_sample = {
        executor: 'constant-arrival-rate',
        rate: SAMPLE_RATE,
        timeUnit: '1s',
        duration: `${sampleDurationSec}s`,
        preAllocatedVUs: parseInt(__ENV.SAMPLE_PRE_VUS || '5', 10),
        maxVUs: parseInt(__ENV.SAMPLE_MAX_VUS || '20', 10),
        exec: 'latencySampleIteration',
    };
}

export const options = {
    scenarios,

    /**
     * p50(med)/p90/p95/p99 를 전 Trend 지표에 공통 적용한다. threshold 가 없는
     * http_req_duration{step:poll}(폴링 응답 지연)도 이 설정이 있어야 요약에 백분위가 잡힌다
     * — threshold 로만 걸린 지표는 그 percentile 만 요약에 실리고 나머지는 비어 나온다.
     */
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],

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
         * e2e 완료 시간: confirm 202 ~ pollStatus DONE/FAILED 수신까지(latency_sample 표본).
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
         * e2e 타임아웃 상한: POLL_TIMEOUT_MS 초과로 종결되지 않은 표본 수(latency_sample).
         * baseline(failRate=0)에서는 타임아웃이 거의 발생하지 않아야 한다.
         */
        'e2e_timeout': ['count<100'],

        /**
         * 부하 시나리오(async_payment) 전용 dropped_iterations/iterations — 제약이 아니라
         * (count>=0 은 항상 참) k6 요약 JSON 에 이 태그 조합을 강제로 실어 내려는 용도다.
         * latency_sample 은 완전 종결까지 폴링하느라 자체적으로 VU 가 부족해질 수 있는데,
         * 그 드롭까지 전역 dropped_iterations 에 섞이면 "부하 도구가 목표 도착률을 실제로
         * 냈는지"를 가리키는 신호(bench-scaleout-cycle.sh 의 load_integrity)가 표본 시나리오의
         * 사정으로 오염된다 — 부하 시나리오 것만 따로 뽑는다.
         */
        'dropped_iterations{scenario:async_payment}': ['count>=0'],
        'iterations{scenario:async_payment}': ['count>=0'],
    },
};

// ---------------------------------------------------------------------------
// 내부 카운터 (handleSummary JSON 출력용)
// ---------------------------------------------------------------------------

/** FAILED 상태로 종결된 건수 (PG 거절 등 정상 실패 포함) — 폴링으로 끝까지 지켜본 건만 센다 */
const paymentFailed = new Counter('payment_failed');

/** 폴링으로 DONE/FAILED 종결까지 실제로 관측한 건수. latency_sample 표본 크기를 그대로 드러낸다 */
const resolvedCompletions = new Counter('e2e_resolved_count');

// ---------------------------------------------------------------------------
// 공유 로직 — checkout + confirm
// ---------------------------------------------------------------------------

/**
 * 주문 생성 + 승인 접수를 수행한다. async_payment(부하)와 latency_sample(표본) 두 시나리오가
 * 공유하는 앞부분이다.
 *
 * @returns {{orderId: string, amount: string, confirmAt: number}|null} 성공 시 확정 접수 정보,
 *   checkout/confirm 실패(F1/F2 오염 차단 포함)면 null — 호출부는 그대로 iteration 을 끝낸다
 */
function performCheckoutAndConfirm() {
    // Step 1: 주문 생성
    const checkoutResponse = doCheckout();

    // Step 2: checkout 201 check (F1 멱등키 충돌 오염 차단)
    const checkoutOk = check(checkoutResponse, {
        'checkout status==201 (중복 200 아님)': (r) => r.status === 201,
    });
    if (!checkoutOk) {
        // 중복 200이거나 서버 오류 — 이 iteration은 측정 제외
        return null;
    }

    const checkoutBody = parseResponseBody(checkoutResponse.body);
    if (checkoutBody === null) {
        return null;
    }

    const orderId = checkoutBody.orderId;
    // checkout 응답에서 totalAmount를 추출해 confirm에 전달한다.
    const amount = checkoutBody.totalAmount
        ? String(checkoutBody.totalAmount)
        : null;

    if (!orderId || !amount) {
        return null;
    }

    // Step 3: 승인 접수
    const confirmResponse = doConfirm(orderId, amount);

    // confirm 202 응답 시각 기록 (폴링 기준점 + 사후 조인 이벤트)
    const confirmAt = Date.now();

    // Step 4: confirm check — 202 확인 + F2 재고 부족 400 미발생 확인
    const confirmOk = check(confirmResponse, {
        'confirm status==202': (r) => r.status === 202,
        'confirm 재고부족 400 미발생 (F2 재고 고갈 차단)': (r) => r.status !== 400,
    });
    if (!confirmOk) {
        // confirm 실패(재고 부족 또는 서버 오류) — 폴링 불필요
        return null;
    }

    // confirm 수락 시각 이벤트 출력 — 사후 DB 처리 시각과 조인하기 위한 기준점
    console.log(JSON.stringify({
        event: 'confirm',
        orderId: orderId,
        confirmAt: confirmAt,
    }));

    return { orderId, amount, confirmAt };
}

/**
 * 종결(DONE/FAILED)까지 폴링하고 e2e 지연·FAILED·타임아웃을 기록한다.
 * async_payment(SKIP_POLL 미지정 시)와 latency_sample 이 공유한다.
 *
 * @param {string} orderId
 * @param {number} confirmAt confirm 202 수신 시각(epochMs)
 */
function resolveByPolling(orderId, confirmAt) {
    const result = pollStatus(orderId);

    if (result === null) {
        // pollStatus 내부에서 e2eTimeout.add(1) 처리됨
        return;
    }

    resolvedCompletions.add(1);

    // e2e 완료 시간 기록 (confirm 202 시각부터 폴링 종결까지)
    const e2eDurationMs = result.resolvedAt - confirmAt;
    e2eCompletionMs.add(e2eDurationMs);

    // 폴링 종결 이벤트 출력 — orderId 기준 DB 처리 시각과 사후 조인
    console.log(JSON.stringify({
        event: 'poll_done',
        orderId: orderId,
        confirmAt: confirmAt,
        resolvedAt: result.resolvedAt,
        status: result.status,
        pollCount: result.pollCount,
        pollEvents: result.pollEvents,
    }));

    if (result.status === 'FAILED') {
        // FAILED는 정상 종결(PG 거절 등), 별도 집계만 수행
        paymentFailed.add(1);
    }
}

// ---------------------------------------------------------------------------
// VU 함수 — 부하 시나리오(async_payment) 엔트리포인트
// ---------------------------------------------------------------------------

/**
 * 부하 본체 iteration. checkout → confirm 접수까지 확인하고, SKIP_POLL=true(bench-scaleout-cycle.sh
 * 고정값)면 곧바로 반환해 VU 를 놓아준다 — 종결 폴링으로 VU 를 붙잡지 않아 목표 도착률을
 * dropped_iterations 없이 실제로 낼 수 있다. SKIP_POLL 미지정이면 옛 방식대로 confirm 이후
 * 폴링까지 마친 뒤 반환한다(sweep.sh 의 비동기 e2e 경로 측정용).
 */
export default function () {
    const accepted = performCheckoutAndConfirm();
    if (accepted === null) {
        return;
    }

    if (__ENV.SKIP_POLL === 'true') {
        return;
    }

    resolveByPolling(accepted.orderId, accepted.confirmAt);
}

/**
 * 지연 표본 시나리오(latency_sample) iteration. 낮은 고정 도착률로 checkout → confirm →
 * 종결(DONE/FAILED)까지 전 과정을 지켜본다. 체감 지연은 이 표본으로만 잰다 — 부하 본체는
 * 더 이상 폴링하지 않으므로 전수 관측이 아니다.
 */
export function latencySampleIteration() {
    const accepted = performCheckoutAndConfirm();
    if (accepted === null) {
        return;
    }

    resolveByPolling(accepted.orderId, accepted.confirmAt);
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
        http_req_duration_poll: extractTrendStats(metrics['http_req_duration{step:poll}']),
        e2e_completion_ms: extractTrendStats(metrics['e2e_completion_ms']),
        e2e_resolved_count: extractCounterValue(metrics['e2e_resolved_count']),
        checks_rate: extractRateValue(metrics['checks']),
        e2e_timeout_count: extractCounterValue(metrics['e2e_timeout']),
        confirm_requests_count: extractCounterValue(metrics['confirm_requests']),
        checkout_duplicate_count: extractCounterValue(metrics['checkout_duplicate']),
        confirm_rejected_count: extractCounterValue(metrics['confirm_rejected']),
        payment_failed_count: extractCounterValue(metrics['payment_failed']),
        // 부하 무결성 — 부하 시나리오(async_payment)에서 발사조차 못 한 반복(dropped)과
        // 실제로 발사된 반복(iterations). latency_sample 의 드롭은 섞지 않는다 — 그
        // 시나리오는 완전 종결까지 폴링하느라 스스로 VU 가 부족해질 수 있고, 그건 부하 도구가
        // 목표 도착률을 냈는지와 무관하다. dropped 가 0이 아니면 이 사이클은 부하 미달이라
        // 처리율 비교에 못 쓴다.
        dropped_iterations_count: extractCounterValue(metrics['dropped_iterations{scenario:async_payment}']),
        iterations_count: extractCounterValue(metrics['iterations{scenario:async_payment}']),
    };
}

/**
 * Trend 메트릭에서 p50/p95/p99/avg/min/max를 추출한다. p50 은 k6 가 median(med)으로
 * 제공하는 값을 그대로 쓴다 — summaryTrendStats 에 'p(50)'을 직접 넣는 대신 median 을
 * 쓰는 것이 k6 표준 방식이다.
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
        p50: v['med'],
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
