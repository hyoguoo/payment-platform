/**
 * 전체 구매 시도를 관측하는 품질 기준 시나리오.
 *
 * k6 run --log-format raw --console-output <attempts.jsonl> \
 *   -e RUN_ID=<unique-id> -e OUTPUT_SUMMARY=<summary.json> \
 *   scripts/k6/quality-payment.js
 *
 * 각 iteration은 checkout 전에 start를 기록하고, 어떤 경로로 끝나든 finish를 기록한다.
 * finish가 없으면 결과 판정기는 해당 시도를 미완료로 간주해 실행을 INCONCLUSIVE로 만든다.
 */
import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const RUN_ID = __ENV.RUN_ID;
if (!RUN_ID || !/^[A-Za-z0-9_-]{1,48}$/.test(RUN_ID)) {
    throw new Error('RUN_ID must contain 1-48 letters, digits, underscores or hyphens');
}

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
const RATE = Number(__ENV.RATE || 10);
const DURATION_SECONDS = Number(__ENV.DURATION_SECONDS || 600);
const PREALLOCATED_VUS = Number(__ENV.PREALLOCATED_VUS || 100);
const PRODUCT_ID_BASE = Number(__ENV.PRODUCT_ID_BASE || 1000);
const PRODUCT_COUNT = 100;
const USER_ID = 1;
const POLL_MS = 500;
const OBSERVATION_MS = 60_000;
const OUTPUT_SUMMARY = __ENV.OUTPUT_SUMMARY || `quality-${RUN_ID}-summary.json`;

if (!Number.isSafeInteger(RATE) || RATE < 1 ||
    !Number.isSafeInteger(DURATION_SECONDS) || DURATION_SECONDS < 1 ||
    !Number.isSafeInteger(PRODUCT_ID_BASE) || PRODUCT_ID_BASE < 1 ||
    !Number.isSafeInteger(PREALLOCATED_VUS) || PREALLOCATED_VUS < 1 || PREALLOCATED_VUS > 650) {
    throw new Error('RATE, DURATION_SECONDS and PRODUCT_ID_BASE must be positive integers');
}

export const options = {
    scenarios: {
        quality_payment: {
            executor: 'constant-arrival-rate',
            rate: RATE,
            timeUnit: '1s',
            duration: `${DURATION_SECONDS}s`,
            preAllocatedVUs: PREALLOCATED_VUS,
            maxVUs: 650,
            gracefulStop: '65s',
        },
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
    thresholds: {
        'dropped_iterations': ['count==0'],
        'quality_confirm_wall_ms': ['p(95)<501'],
    },
};

const confirmWall = new Trend('quality_confirm_wall_ms', true);
const started = new Counter('quality_started');
const done = new Counter('quality_done');

function parseBody(body) {
    try {
        const parsed = JSON.parse(body);
        return parsed && parsed.data !== undefined ? parsed.data : parsed;
    } catch (_) {
        return null;
    }
}

function requestTimeout(deadlineMs) {
    return `${Math.max(1, deadlineMs - Date.now())}ms`;
}

function recordError(record, step, status) {
    record.apiErrors.push(`${step}_http_${status}`);
}

export default function () {
    const iteration = exec.scenario.iterationInTest;
    const attemptId = `${RUN_ID}-${iteration}`;
    const idempotencyKey = `quality-${attemptId}`;
    const productId = PRODUCT_ID_BASE + iteration % PRODUCT_COUNT;
    const startMs = Date.now();
    const deadlineMs = startMs + OBSERVATION_MS;
    const common = {
        runId: RUN_ID, attemptId, idempotencyKey, productId, startMs,
        scenarioStartMs: exec.scenario.startTime,
    };
    const result = {
        ...common, kind: 'finish', orderId: null, confirmStartMs: null,
        confirmEndMs: null, doneMs: null, finishedMs: null,
        outcome: 'CHECKOUT_ERROR', checkoutStatus: null, confirmStatus: null,
        apiErrors: [], pollCount: 0,
    };

    // 이 로그가 분모의 근거다. HTTP 호출보다 먼저 써야 checkout 오류가 빠지지 않는다.
    console.log(JSON.stringify({ ...common, kind: 'start' }));
    started.add(1);
    try {
        const checkout = http.post(`${BASE_URL}/api/v1/payments/checkout`, JSON.stringify({
            userId: USER_ID,
            orderedProductList: [{ productId, quantity: 1 }],
            gatewayType: 'TOSS',
        }), {
            headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
            tags: { step: 'checkout' }, timeout: requestTimeout(deadlineMs),
        });
        result.checkoutStatus = checkout.status;
        const checkoutBody = parseBody(checkout.body);
        if (checkoutBody && typeof checkoutBody.orderId === 'string') {
            result.orderId = checkoutBody.orderId;
        }
        if (checkout.status !== 201) {
            recordError(result, 'checkout', checkout.status);
        }
        if (checkout.status !== 201 || !result.orderId ||
            !checkoutBody || checkoutBody.totalAmount === undefined) {
            return;
        }

        result.confirmStartMs = Date.now();
        const confirm = http.post(`${BASE_URL}/api/v1/payments/confirm`, JSON.stringify({
            userId: USER_ID,
            orderId: result.orderId,
            amount: String(checkoutBody.totalAmount),
            paymentKey: `quality-key-${attemptId}`,
            gatewayType: 'TOSS',
        }), {
            headers: { 'Content-Type': 'application/json' },
            tags: { step: 'confirm' }, timeout: requestTimeout(deadlineMs),
        });
        result.confirmEndMs = Date.now();
        result.confirmStatus = confirm.status;
        confirmWall.add(result.confirmEndMs - result.confirmStartMs);
        if (confirm.status !== 202) {
            recordError(result, 'confirm', confirm.status);
        }

        // 202가 유실된 경우에도 서버에서 처리됐을 수 있으므로 orderId가 있으면 관측한다.
        result.outcome = 'TIMEOUT';
        while (Date.now() < deadlineMs) {
            const statusResponse = http.get(
                `${BASE_URL}/api/v1/payments/${encodeURIComponent(result.orderId)}/status`,
                { tags: { step: 'status' }, timeout: requestTimeout(deadlineMs) }
            );
            result.pollCount += 1;
            const observedAt = Date.now();
            if (statusResponse.status === 200) {
                const body = parseBody(statusResponse.body);
                if (!body || !body.orderId) {
                    result.apiErrors.push('status_order_missing');
                } else if (body.orderId !== result.orderId) {
                    result.apiErrors.push('status_order_mismatch');
                } else if (body.status === 'DONE') {
                    if (observedAt <= deadlineMs) {
                        result.doneMs = observedAt;
                        result.outcome = 'DONE';
                        done.add(1);
                    }
                    return;
                } else if (body.status === 'FAILED' || body.status === 'QUARANTINED') {
                    result.outcome = body.status;
                    return;
                }
            } else {
                recordError(result, 'status', statusResponse.status);
            }
            const remainingMs = deadlineMs - Date.now();
            if (remainingMs > 0) {
                sleep(Math.min(POLL_MS, remainingMs) / 1000);
            }
        }
    } catch (error) {
        result.apiErrors.push(`exception:${String(error).slice(0, 200)}`);
        result.outcome = 'EXCEPTION';
    } finally {
        result.finishedMs = Date.now();
        console.log(JSON.stringify(result));
    }
}

export function handleSummary(data) {
    return { [OUTPUT_SUMMARY]: JSON.stringify(data, null, 2) + '\n' };
}
