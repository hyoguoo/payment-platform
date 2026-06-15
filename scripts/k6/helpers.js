/**
 * k6 부하 측정 공통 헬퍼
 *
 * 용도: async-payment.js 등 k6 시나리오 스크립트에서 import해 사용.
 *       커스텀 메트릭 정의, 환경 상수, 요청 헬퍼 함수를 한 곳에서 관리한다.
 *
 * 부하 곡선(RAMPING_ARRIVAL_RATE_STAGES)은 baseline 값을 초기값으로 사용하며,
 * 실측 결과에 따라 단계별 target을 조정한다.
 */

import { Trend, Counter } from 'k6/metrics';
import http from 'k6/http';
import { sleep } from 'k6';

// ---------------------------------------------------------------------------
// 환경 기반 상수 (__ENV 주입, 미설정 시 기본값 사용)
// ---------------------------------------------------------------------------

/** payment-service Gateway 기저 URL */
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

/** 측정에 사용할 사용자 ID (seed에서 생성한 user id=1) */
export const USER_ID = parseInt(__ENV.USER_ID || '1', 10);

/** 측정에 사용할 상품 ID (bench-seed-stock.sh로 재고 확보한 product id=1) */
export const PRODUCT_ID = parseInt(__ENV.PRODUCT_ID || '1', 10);

/** 1회 주문 수량 */
export const QUANTITY = parseInt(__ENV.QUANTITY || '1', 10);

/**
 * confirm 요청에 사용할 결제 금액.
 * 실제 금액 검증은 서버가 수행하며, checkout 응답 totalAmount와 일치해야 한다.
 * 이 상수는 단일 상품 단가가 알려진 경우에만 직접 사용한다.
 * 일반적으로는 checkout 응답에서 추출한 totalAmount를 confirm에 전달한다.
 */
export const AMOUNT = __ENV.AMOUNT || '1000';

/** 사용할 PG 타입 (PaymentGatewayType enum 값) */
export const GATEWAY_TYPE = __ENV.GATEWAY_TYPE || 'TOSS';

/**
 * 상태 폴링 기본 간격(ms).
 * outbox worker 폴백 주기(2s)보다 작게 잡아 DONE/FAILED 전환 직후 빠르게 감지한다.
 */
export const POLL_INTERVAL_MS = parseInt(__ENV.POLL_INTERVAL_MS || '500', 10);

/**
 * 상태 폴링 최대 대기 시간(ms).
 * outbox worker 폴백 주기(2s)보다 반드시 커야 한다(하한 2000ms).
 * reconciler in-flight-timeout 단축값(Task1 RECONCILER_IN_FLIGHT_TIMEOUT_SECONDS)보다도
 * 충분히 작게 잡아야 타임아웃 카운트가 settle 이전 미완료를 정확히 반영한다.
 */
export const POLL_TIMEOUT_MS = Math.max(
    2000,
    parseInt(__ENV.POLL_TIMEOUT_MS || '10000', 10)
);

// ---------------------------------------------------------------------------
// 부하 곡선 상수
// ---------------------------------------------------------------------------

/**
 * ramping-arrival-rate executor용 단계 배열.
 * baseline: 100 → 200 → 400 req/s (웜업/중간 단계 60s, 피크 120s).
 * env 로 조정 가능(측정하며 조정 — PLAN.md Task 3 부하 곡선 결정 참조):
 *   PEAK_RATE  — 피크 목표 req/s (기본 400). 웜업=1/4, 중간=1/2 로 자동 산출
 *   STAGE_SEC  — 웜업/중간 단계 길이(초, 기본 60). 피크는 2×, 쿨다운은 30s 고정
 * smoke run 예: PEAK_RATE=20 STAGE_SEC=10
 */
export const PEAK_RATE = parseInt(__ENV.PEAK_RATE || '400', 10);
const STAGE_SEC = parseInt(__ENV.STAGE_SEC || '60', 10);
export const RAMPING_ARRIVAL_RATE_STAGES = [
    { duration: `${STAGE_SEC}s`, target: Math.max(1, Math.round(PEAK_RATE / 4)) },  // 웜업
    { duration: `${STAGE_SEC}s`, target: Math.max(1, Math.round(PEAK_RATE / 2)) },  // 중간 부하
    { duration: `${STAGE_SEC * 2}s`, target: PEAK_RATE },                            // 피크 부하
    { duration: '30s', target: 0 },                                                  // 쿨다운
];

// ---------------------------------------------------------------------------
// 커스텀 메트릭
// ---------------------------------------------------------------------------

/** e2e 완료 시간(ms): checkout 요청 ~ pollStatus DONE/FAILED 수신까지 */
export const e2eCompletionMs = new Trend('e2e_completion_ms', true);

/** 상태 폴링 타임아웃 카운트: POLL_TIMEOUT_MS 초과 시 증가 */
export const e2eTimeout = new Counter('e2e_timeout');

/** confirm 요청 총 카운트 */
export const confirmRequests = new Counter('confirm_requests');

/**
 * checkout 중복 카운트: checkout 응답 HTTP status == 200 인 경우.
 * 신규 주문은 201, 멱등키 중복(동일 키 재요청)은 200으로 구별한다.
 * 고유 멱등키 정책을 사용하므로 정상 측정 시 이 카운터는 0이어야 한다.
 */
export const checkoutDuplicate = new Counter('checkout_duplicate');

/**
 * confirm 거부 카운트: confirm 응답 HTTP status == 400 인 경우.
 * 재고 부족(PaymentOrderedProductStockException → BAD_REQUEST) 시 증가한다.
 * bench-seed-stock.sh로 충분한 재고를 확보한 baseline에서는 0이어야 한다.
 */
export const confirmRejected = new Counter('confirm_rejected');

// ---------------------------------------------------------------------------
// 요청 헬퍼
// ---------------------------------------------------------------------------

/**
 * 고유 멱등키 생성.
 * VU(가상 사용자) ID + 반복 횟수 + 무작위 UUID 조합으로 충돌을 방지한다.
 *
 * @returns {string} 형식: "{__VU}-{__ITER}-{uuid}"
 */
export function uniqueIdempotencyKey() {
    const uuid = generateUUID();
    return `${__VU}-${__ITER}-${uuid}`;
}

/**
 * 주문 생성 요청 (POST /api/v1/payments/checkout).
 *
 * - 고유 Idempotency-Key 헤더를 반드시 포함한다.
 * - 응답 HTTP 201: 신규 주문 생성 / 200: 멱등키 중복(재요청).
 * - 태그 step:checkout으로 k6 메트릭 단계 구분.
 *
 * CheckoutRequest 필드:
 *   userId     Long
 *   orderedProductList  [{productId: Long, quantity: Integer}]
 *   gatewayType         PaymentGatewayType (예: "TOSS")
 *
 * @param {string} [idempotencyKey] 고유 멱등키 (미전달 시 자동 생성)
 * @returns {Response} k6 HTTP 응답 객체
 */
export function doCheckout(idempotencyKey) {
    const key = idempotencyKey || uniqueIdempotencyKey();

    const payload = JSON.stringify({
        userId: USER_ID,
        orderedProductList: [
            {
                productId: PRODUCT_ID,
                quantity: QUANTITY,
            },
        ],
        gatewayType: GATEWAY_TYPE,
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': key,
        },
        tags: { step: 'checkout' },
    };

    const response = http.post(`${BASE_URL}/api/v1/payments/checkout`, payload, params);

    if (response.status === 200) {
        checkoutDuplicate.add(1);
    }

    return response;
}

/**
 * 결제 승인 요청 (POST /api/v1/payments/confirm).
 *
 * - 비동기 처리: 서버는 동기 재고 차감 + TX 후 202 Accepted 반환.
 * - paymentKey는 임의 문자열로 주입(첫 confirm 시 저장된 paymentKey가 null이라 통과).
 * - 태그 step:confirm으로 k6 메트릭 단계 구분.
 * - 성공 시 confirmRequests 카운터 증가.
 * - 재고 부족(400) 시 confirmRejected 카운터 증가.
 *
 * PaymentConfirmRequest 필드:
 *   userId      Long
 *   orderId     String
 *   amount      BigDecimal (문자열 직렬화)
 *   paymentKey  String (임의값)
 *   gatewayType PaymentGatewayType (예: "TOSS")
 *
 * @param {string} orderId   checkout 응답에서 추출한 orderId
 * @param {string} amount    checkout 응답에서 추출한 totalAmount (문자열)
 * @returns {Response} k6 HTTP 응답 객체
 */
export function doConfirm(orderId, amount) {
    const paymentKey = `bench-key-${__VU}-${__ITER}-${generateUUID()}`;

    const payload = JSON.stringify({
        userId: USER_ID,
        orderId: orderId,
        amount: amount,
        paymentKey: paymentKey,
        gatewayType: GATEWAY_TYPE,
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
        },
        tags: { step: 'confirm' },
    };

    const response = http.post(`${BASE_URL}/api/v1/payments/confirm`, payload, params);

    confirmRequests.add(1);

    if (response.status === 400) {
        confirmRejected.add(1);
    }

    return response;
}

/**
 * 결제 상태 폴링 (GET /api/v1/payments/{orderId}/status).
 *
 * - DONE 또는 FAILED 상태에서 폴링을 종료한다.
 * - 폴링 간격과 최대 대기 시간을 인자로 받아 유연하게 조정 가능.
 * - 타임아웃 시 e2eTimeout 카운터를 증가시키고 null을 반환한다.
 * - 타임아웃 하한: outbox worker 폴백 주기(2s) 이상을 보장한다.
 *
 * PaymentStatusApiResponse 필드:
 *   orderId     String
 *   status      PENDING | PROCESSING | DONE | FAILED
 *   approvedAt  Instant (null 가능)
 *
 * @param {string} orderId           폴링할 주문 ID
 * @param {number} [intervalMs]      폴링 간격(ms), 기본값: POLL_INTERVAL_MS
 * @param {number} [timeoutMs]       최대 폴링 대기 시간(ms), 기본값: POLL_TIMEOUT_MS (하한 2000ms 보장)
 * @returns {{ status: string, approvedAt: string|null }|null} 종결 상태 객체, 타임아웃 시 null
 */
export function pollStatus(orderId, intervalMs, timeoutMs) {
    const interval = intervalMs || POLL_INTERVAL_MS;
    const timeout = Math.max(2000, timeoutMs || POLL_TIMEOUT_MS);

    const params = {
        headers: {
            'Content-Type': 'application/json',
        },
        tags: { step: 'poll' },
    };

    const startTime = Date.now();

    while (true) {
        const response = http.get(
            `${BASE_URL}/api/v1/payments/${orderId}/status`,
            params
        );

        if (response.status === 200) {
            const body = parseJSON(response.body);

            if (body !== null && (body.status === 'DONE' || body.status === 'FAILED')) {
                return { status: body.status, approvedAt: body.approvedAt || null };
            }
        }

        const elapsed = Date.now() - startTime;
        if (elapsed >= timeout) {
            e2eTimeout.add(1);
            return null;
        }

        sleep(interval / 1000);
    }
}

// ---------------------------------------------------------------------------
// 내부 유틸리티
// ---------------------------------------------------------------------------

/**
 * UUID v4 생성 (k6 내장 crypto 없이 Math.random 기반 구현).
 * 충돌 가능성이 극히 낮으며, 멱등키 고유성 확보 목적으로만 사용한다.
 *
 * @returns {string} UUID v4 형식 문자열
 */
function generateUUID() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
        const r = Math.random() * 16 | 0;
        const v = c === 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}

/**
 * JSON 파싱 헬퍼. 파싱 실패 시 null을 반환한다.
 *
 * @param {string} text JSON 문자열
 * @returns {object|null} 파싱 결과 또는 null
 */
function parseJSON(text) {
    try {
        const parsed = JSON.parse(text);
        // 공통 응답 래퍼 {data:...} 대응 — data 키가 있으면 벗기고, 없으면 원본 반환
        return parsed && parsed.data !== undefined ? parsed.data : parsed;
    } catch (_) {
        return null;
    }
}
