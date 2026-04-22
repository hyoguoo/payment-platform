#!/usr/bin/env bash
# Phase 1 Gate — 결제 코어 E2E 검증 스크립트
# MSA-TRANSITION T1-Gate
#
# 전제조건:
#   - docker, curl, jq, mysql CLI 설치 필요
#   - 인프라 + Gateway + payment-service 기동 완료 필요:
#       docker compose -f docker-compose.infra.yml up -d
#       # Gateway (포트 8080), payment-service (포트 8081) 별도 기동
#
# 실행:
#   bash scripts/phase-gate/phase-1-gate.sh
#
# 결과:
#   - 각 체크마다 [PASS] / [FAIL] 출력
#   - 전부 PASS → exit 0 (Phase 2.a 진입 가능)
#   - 하나라도 FAIL → exit 1 (원인 수정 후 재실행)

set -euo pipefail

# ─────────────────────────────────────────────
# 색상/상수
# ─────────────────────────────────────────────
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

PASS_COUNT=0
FAIL_COUNT=0
FAIL_ITEMS=()

# 엔드포인트 설정 (환경 변수로 재정의 가능)
GATEWAY_BASE="${GATEWAY_BASE:-http://localhost:8080}"
PAYMENT_SERVICE_BASE="${PAYMENT_SERVICE_BASE:-http://localhost:8081}"

# 결제 서비스 DB 접속 정보
PAYMENT_DB_HOST="${PAYMENT_DB_HOST:-127.0.0.1}"
PAYMENT_DB_PORT="${PAYMENT_DB_PORT:-3307}"
PAYMENT_DB_NAME="${PAYMENT_DB_NAME:-payment}"
PAYMENT_DB_USER="${PAYMENT_DB_USER:-payment}"
PAYMENT_DB_PASS="${PAYMENT_DB_PASS:-payment123}"

# Redis (재고 캐시 전용 — 포트 6380)
REDIS_CONTAINER="${REDIS_CONTAINER:-payment-redis-stock}"

# E2E 비동기 대기 설정
ASYNC_WAIT_SECONDS="${ASYNC_WAIT_SECONDS:-10}"
ASYNC_POLL_INTERVAL="${ASYNC_POLL_INTERVAL:-2}"

# ─────────────────────────────────────────────
# 헬퍼 함수
# ─────────────────────────────────────────────
pass() {
  local name="$1"
  echo -e "${GREEN}[PASS]${NC} ${name}"
  PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
  local name="$1"
  local reason="${2:-}"
  echo -e "${RED}[FAIL]${NC} ${name}${reason:+ — ${reason}}"
  FAIL_COUNT=$((FAIL_COUNT + 1))
  FAIL_ITEMS+=("${name}")
}

section() {
  echo ""
  echo -e "${YELLOW}═══ $1 ═══${NC}"
}

# DB 쿼리 실행 헬퍼
payment_db_query() {
  mysql \
    -h "${PAYMENT_DB_HOST}" \
    -P "${PAYMENT_DB_PORT}" \
    -u "${PAYMENT_DB_USER}" \
    -p"${PAYMENT_DB_PASS}" \
    --batch \
    --skip-column-names \
    "${PAYMENT_DB_NAME}" \
    -e "$1" 2>/dev/null || echo ""
}

# 비동기 상태 폴링 — 지정 상태가 될 때까지 대기
# 반환: 최종 상태 문자열 (또는 빈 문자열)
wait_for_payment_status() {
  local order_id="$1"
  local expected_status="$2"
  local waited=0

  while [[ "${waited}" -lt "${ASYNC_WAIT_SECONDS}" ]]; do
    local status
    status=$(payment_db_query \
      "SELECT status FROM payment_event WHERE order_id = '${order_id}' LIMIT 1;")
    if [[ "${status}" == "${expected_status}" ]]; then
      echo "${status}"
      return 0
    fi
    sleep "${ASYNC_POLL_INTERVAL}"
    waited=$((waited + ASYNC_POLL_INTERVAL))
  done

  # 타임아웃 — 마지막 상태 반환
  payment_db_query \
    "SELECT status FROM payment_event WHERE order_id = '${order_id}' LIMIT 1;"
}

# Redis HGET 래퍼 (stock:{productId})
redis_get_stock() {
  local product_id="$1"
  docker exec "${REDIS_CONTAINER}" redis-cli GET "stock:${product_id}" 2>/dev/null || echo ""
}

# ─────────────────────────────────────────────
# 전제조건 확인
# ─────────────────────────────────────────────
section "전제조건 확인"

for cmd in docker curl jq mysql; do
  if command -v "${cmd}" >/dev/null 2>&1; then
    pass "명령어 존재: ${cmd}"
  else
    fail "명령어 존재: ${cmd}" "${cmd} 미설치"
  fi
done

# ─────────────────────────────────────────────
# 1. 인프라 헬스체크
# ─────────────────────────────────────────────
section "1. 인프라 헬스체크"

# 1a. Gateway /actuator/health
GATEWAY_HEALTH=$(curl -sf "${GATEWAY_BASE}/actuator/health" 2>/dev/null || echo "")
if [[ -n "${GATEWAY_HEALTH}" ]]; then
  GW_STATUS=$(echo "${GATEWAY_HEALTH}" | jq -r '.status' 2>/dev/null || echo "")
  if [[ "${GW_STATUS}" == "UP" ]]; then
    pass "Gateway /actuator/health → UP"
  else
    fail "Gateway /actuator/health → UP" "status=${GW_STATUS:-파싱실패}"
  fi
else
  fail "Gateway /actuator/health" "curl 실패 (${GATEWAY_BASE}/actuator/health)"
fi

# 1b. payment-service /actuator/health
PS_HEALTH=$(curl -sf "${PAYMENT_SERVICE_BASE}/actuator/health" 2>/dev/null || echo "")
if [[ -n "${PS_HEALTH}" ]]; then
  PS_STATUS=$(echo "${PS_HEALTH}" | jq -r '.status' 2>/dev/null || echo "")
  if [[ "${PS_STATUS}" == "UP" ]]; then
    pass "payment-service /actuator/health → UP"
  else
    fail "payment-service /actuator/health → UP" "status=${PS_STATUS:-파싱실패}"
  fi
else
  fail "payment-service /actuator/health" "curl 실패 (${PAYMENT_SERVICE_BASE}/actuator/health)"
fi

# 1c. Redis PING (결제 전용)
REDIS_PING=$(docker exec "${REDIS_CONTAINER}" redis-cli ping 2>/dev/null || echo "")
if [[ "${REDIS_PING}" == "PONG" ]]; then
  pass "결제 Redis PING → PONG"
else
  fail "결제 Redis PING" "응답=${REDIS_PING:-없음}"
fi

# 1d. MySQL payment DB 접속
MYSQL_OK=$(payment_db_query "SELECT 1;")
if [[ "${MYSQL_OK}" == "1" ]]; then
  pass "payment MySQL DB 접속 성공"
else
  fail "payment MySQL DB 접속" "쿼리 실패 — DB 미기동 또는 접속 정보 오류"
fi

# 1e. Kafka 브로커 응답
if docker exec payment-kafka kafka-topics \
    --bootstrap-server localhost:9092 \
    --list >/dev/null 2>&1; then
  pass "Kafka 브로커 응답 (bootstrap-server localhost:9092)"
else
  fail "Kafka 브로커 응답" "kafka-topics --list 실패"
fi

# ─────────────────────────────────────────────
# 2. Flyway 마이그레이션 상태 확인
# ─────────────────────────────────────────────
section "2. Flyway 마이그레이션 상태"

FLYWAY_VERSION=$(payment_db_query \
  "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;" 2>/dev/null || echo "")

if [[ -n "${FLYWAY_VERSION}" ]]; then
  pass "Flyway 마이그레이션 완료 (최신 버전: ${FLYWAY_VERSION})"
else
  fail "Flyway 마이그레이션" "flyway_schema_history 조회 실패 — 마이그레이션 미실행 가능성"
fi

FLYWAY_FAILED=$(payment_db_query \
  "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0;" 2>/dev/null || echo "0")
if [[ "${FLYWAY_FAILED}" == "0" ]]; then
  pass "Flyway 실패 마이그레이션 0건"
else
  fail "Flyway 실패 마이그레이션" "${FLYWAY_FAILED}건 실패 — flyway_schema_history 확인"
fi

# payment_event 테이블 존재 확인
PE_TABLE=$(payment_db_query \
  "SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema='${PAYMENT_DB_NAME}' AND table_name='payment_event';" 2>/dev/null || echo "0")
if [[ "${PE_TABLE}" == "1" ]]; then
  pass "테이블 존재: payment_event"
else
  fail "테이블 존재: payment_event" "Flyway V1 미실행 가능성"
fi

# payment_outbox 테이블 존재 확인
PO_TABLE=$(payment_db_query \
  "SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema='${PAYMENT_DB_NAME}' AND table_name='payment_outbox';" 2>/dev/null || echo "0")
if [[ "${PO_TABLE}" == "1" ]]; then
  pass "테이블 존재: payment_outbox"
else
  fail "테이블 존재: payment_outbox" "Flyway V1 미실행 가능성"
fi

# ─────────────────────────────────────────────
# 3. E2E 테스트 데이터 시드
# ─────────────────────────────────────────────
section "3. E2E 테스트 데이터 시드"

# 테스트에 사용할 고유 suffix (PID 기반)
SUFFIX="gate1-$$"
ORDER_SUCCESS="order-success-${SUFFIX}"
ORDER_FAILED="order-failed-${SUFFIX}"
ORDER_QUARANTINE="order-quarantine-${SUFFIX}"

# 테스트 product — 재고 10
TEST_PRODUCT_ID=99901

# Redis 재고 캐시 초기화 (재현성 보장)
docker exec "${REDIS_CONTAINER}" redis-cli SET "stock:${TEST_PRODUCT_ID}" 10 EX 3600 >/dev/null 2>&1 || true
STOCK_BEFORE=$(redis_get_stock "${TEST_PRODUCT_ID}")
echo "  [INFO] 시드 Redis stock:${TEST_PRODUCT_ID} = ${STOCK_BEFORE}"

# 성공 경로 결제 이벤트 INSERT (READY 상태)
payment_db_query "
INSERT INTO payment_event
    (buyer_id, seller_id, order_name, order_id, payment_key, gateway_type,
     status, retry_count, quarantine_compensation_pending, created_at, updated_at)
VALUES
    (1, 2, 'Gate1 성공 테스트', '${ORDER_SUCCESS}', 'pay-key-success-${SUFFIX}',
     'TOSS', 'READY', 0, false, NOW(), NOW());
" 2>/dev/null && pass "시드: 성공 경로 결제 이벤트 INSERT (${ORDER_SUCCESS})" \
             || fail "시드: 성공 경로 결제 이벤트 INSERT" "DB INSERT 실패"

payment_db_query "
INSERT INTO payment_order
    (payment_event_id, order_id, product_id, quantity, amount, status, created_at, updated_at)
SELECT id, '${ORDER_SUCCESS}', ${TEST_PRODUCT_ID}, 1, 10000.00, 'READY', NOW(), NOW()
FROM payment_event WHERE order_id = '${ORDER_SUCCESS}';
" 2>/dev/null && pass "시드: 성공 경로 payment_order INSERT" \
             || fail "시드: 성공 경로 payment_order INSERT" "DB INSERT 실패"

# 실패 경로 결제 이벤트 INSERT (READY 상태 → PG 실패 시뮬)
payment_db_query "
INSERT INTO payment_event
    (buyer_id, seller_id, order_name, order_id, payment_key, gateway_type,
     status, retry_count, quarantine_compensation_pending, created_at, updated_at)
VALUES
    (1, 2, 'Gate1 실패 테스트', '${ORDER_FAILED}', 'pay-key-failed-${SUFFIX}',
     'TOSS', 'READY', 0, false, NOW(), NOW());
" 2>/dev/null && pass "시드: 실패 경로 결제 이벤트 INSERT (${ORDER_FAILED})" \
             || fail "시드: 실패 경로 결제 이벤트 INSERT" "DB INSERT 실패"

payment_db_query "
INSERT INTO payment_order
    (payment_event_id, order_id, product_id, quantity, amount, status, created_at, updated_at)
SELECT id, '${ORDER_FAILED}', ${TEST_PRODUCT_ID}, 1, 10000.00, 'READY', NOW(), NOW()
FROM payment_event WHERE order_id = '${ORDER_FAILED}';
" 2>/dev/null && pass "시드: 실패 경로 payment_order INSERT" \
             || fail "시드: 실패 경로 payment_order INSERT" "DB INSERT 실패"

# QUARANTINED 경로 — 재고 부족 시뮬 (재고 캐시를 0으로 설정)
docker exec "${REDIS_CONTAINER}" redis-cli SET "stock:${TEST_PRODUCT_ID}" 0 EX 3600 >/dev/null 2>&1 || true

payment_db_query "
INSERT INTO payment_event
    (buyer_id, seller_id, order_name, order_id, payment_key, gateway_type,
     status, retry_count, quarantine_compensation_pending, created_at, updated_at)
VALUES
    (1, 2, 'Gate1 격리 테스트', '${ORDER_QUARANTINE}', 'pay-key-quarantine-${SUFFIX}',
     'TOSS', 'READY', 0, false, NOW(), NOW());
" 2>/dev/null && pass "시드: QUARANTINED 경로 결제 이벤트 INSERT (${ORDER_QUARANTINE})" \
             || fail "시드: QUARANTINED 경로 결제 이벤트 INSERT" "DB INSERT 실패"

payment_db_query "
INSERT INTO payment_order
    (payment_event_id, order_id, product_id, quantity, amount, status, created_at, updated_at)
SELECT id, '${ORDER_QUARANTINE}', ${TEST_PRODUCT_ID}, 1, 10000.00, 'READY', NOW(), NOW()
FROM payment_event WHERE order_id = '${ORDER_QUARANTINE}';
" 2>/dev/null && pass "시드: QUARANTINED 경로 payment_order INSERT" \
             || fail "시드: QUARANTINED 경로 payment_order INSERT" "DB INSERT 실패"

# 재고 캐시를 성공/실패 테스트를 위해 복원
docker exec "${REDIS_CONTAINER}" redis-cli SET "stock:${TEST_PRODUCT_ID}" 10 EX 3600 >/dev/null 2>&1 || true

# ─────────────────────────────────────────────
# 4. 결제 성공 경로 E2E
# ─────────────────────────────────────────────
section "4. 결제 성공 경로 E2E"

# 4a. confirm POST → Gateway 경유 → payment-service
echo "  [INFO] POST ${GATEWAY_BASE}/api/v1/payments/confirm (성공 경로)"
CONFIRM_SUCCESS_RESP=$(curl -sf -X POST \
  "${GATEWAY_BASE}/api/v1/payments/confirm" \
  -H "Content-Type: application/json" \
  -d "{
    \"orderId\":   \"${ORDER_SUCCESS}\",
    \"paymentKey\": \"pay-key-success-${SUFFIX}\",
    \"amount\":    10000
  }" 2>/dev/null || echo "CURL_FAIL")

if [[ "${CONFIRM_SUCCESS_RESP}" == "CURL_FAIL" || -z "${CONFIRM_SUCCESS_RESP}" ]]; then
  echo "  [INFO] confirm 응답 없음 — 비동기 경로 가정, DB 상태 확인으로 대체"
else
  echo "  [INFO] confirm 응답: ${CONFIRM_SUCCESS_RESP}"
fi

# 4b. 비동기 완료 대기 후 DB 상태 확인 (DONE)
echo "  [INFO] 비동기 처리 대기 (최대 ${ASYNC_WAIT_SECONDS}초)..."
STATUS_SUCCESS=$(wait_for_payment_status "${ORDER_SUCCESS}" "DONE")
if [[ "${STATUS_SUCCESS}" == "DONE" ]]; then
  pass "성공 경로: payment_event.status = DONE (orderId=${ORDER_SUCCESS})"
else
  fail "성공 경로: status = DONE" "실제 status=${STATUS_SUCCESS:-없음} (orderId=${ORDER_SUCCESS})"
fi

# ─────────────────────────────────────────────
# 5. 결제 실패 경로 E2E
# ─────────────────────────────────────────────
section "5. 결제 실패 경로 E2E (PG 거절 시뮬)"

# 5a. confirm POST — 의도적으로 잘못된 paymentKey로 PG 거절 유도
echo "  [INFO] POST ${GATEWAY_BASE}/api/v1/payments/confirm (실패 경로 — PG 거절 시뮬)"
CONFIRM_FAILED_RESP=$(curl -sf -X POST \
  "${GATEWAY_BASE}/api/v1/payments/confirm" \
  -H "Content-Type: application/json" \
  -d "{
    \"orderId\":   \"${ORDER_FAILED}\",
    \"paymentKey\": \"INVALID-KEY-WILL-FAIL-${SUFFIX}\",
    \"amount\":    10000
  }" 2>/dev/null || echo "CURL_FAIL")

if [[ "${CONFIRM_FAILED_RESP}" == "CURL_FAIL" || -z "${CONFIRM_FAILED_RESP}" ]]; then
  echo "  [INFO] confirm 응답 없음 — 비동기 경로 가정, DB 상태 확인으로 대체"
else
  echo "  [INFO] confirm 응답: ${CONFIRM_FAILED_RESP}"
fi

# 5b. 비동기 완료 대기 후 DB 상태 확인 (FAILED)
echo "  [INFO] 비동기 처리 대기 (최대 ${ASYNC_WAIT_SECONDS}초)..."
STATUS_FAILED=$(wait_for_payment_status "${ORDER_FAILED}" "FAILED")
if [[ "${STATUS_FAILED}" == "FAILED" ]]; then
  pass "실패 경로: payment_event.status = FAILED (orderId=${ORDER_FAILED})"
else
  # 실패 경로는 서버가 PG Sandbox에 연결하지 않으면 검증 한계 있음 — WARN으로 처리
  echo -e "${YELLOW}[WARN]${NC} 실패 경로: status=${STATUS_FAILED:-READY} — PG Sandbox 미연결 환경에서는 FAILED 전환이 발생하지 않을 수 있음 (수동 확인 권장)"
  FAIL_COUNT=$((FAIL_COUNT + 1))
  FAIL_ITEMS+=("실패 경로: status = FAILED (PG Sandbox 필요)")
fi

# ─────────────────────────────────────────────
# 6. QUARANTINED 경로 E2E (재고 부족 시뮬)
# ─────────────────────────────────────────────
section "6. QUARANTINED 경로 E2E (재고 부족 시뮬)"

# 재고 0 상태에서 confirm 시도
docker exec "${REDIS_CONTAINER}" redis-cli SET "stock:${TEST_PRODUCT_ID}" 0 EX 3600 >/dev/null 2>&1 || true

echo "  [INFO] POST ${GATEWAY_BASE}/api/v1/payments/confirm (QUARANTINED 경로 — 재고 부족)"
CONFIRM_QUARANTINE_RESP=$(curl -sf -X POST \
  "${GATEWAY_BASE}/api/v1/payments/confirm" \
  -H "Content-Type: application/json" \
  -d "{
    \"orderId\":   \"${ORDER_QUARANTINE}\",
    \"paymentKey\": \"pay-key-quarantine-${SUFFIX}\",
    \"amount\":    10000
  }" 2>/dev/null || echo "CURL_FAIL")

if [[ "${CONFIRM_QUARANTINE_RESP}" == "CURL_FAIL" || -z "${CONFIRM_QUARANTINE_RESP}" ]]; then
  echo "  [INFO] confirm 응답 없음 — 비동기 경로 가정, DB 상태 확인으로 대체"
else
  echo "  [INFO] confirm 응답: ${CONFIRM_QUARANTINE_RESP}"
fi

echo "  [INFO] 비동기 처리 대기 (최대 ${ASYNC_WAIT_SECONDS}초)..."
STATUS_QUARANTINE=$(wait_for_payment_status "${ORDER_QUARANTINE}" "QUARANTINED")
if [[ "${STATUS_QUARANTINE}" == "QUARANTINED" ]]; then
  pass "QUARANTINED 경로: payment_event.status = QUARANTINED (orderId=${ORDER_QUARANTINE})"
else
  echo -e "${YELLOW}[WARN]${NC} QUARANTINED 경로: status=${STATUS_QUARANTINE:-READY} — 재고 부족 트리거가 비동기 처리 완료 전일 수 있음 (수동 확인 권장)"
  FAIL_COUNT=$((FAIL_COUNT + 1))
  FAIL_ITEMS+=("QUARANTINED 경로: status = QUARANTINED")
fi

# 재고 캐시 복원
docker exec "${REDIS_CONTAINER}" redis-cli SET "stock:${TEST_PRODUCT_ID}" 10 EX 3600 >/dev/null 2>&1 || true

# ─────────────────────────────────────────────
# 7. Redis DECR 확인 (stock:{productId} 감소)
# ─────────────────────────────────────────────
section "7. Redis DECR 확인 (stock:{productId})"

# 성공 경로 완료 후 재고가 10 → 9 감소했는지 확인
STOCK_AFTER=$(redis_get_stock "${TEST_PRODUCT_ID}")
echo "  [INFO] stock:${TEST_PRODUCT_ID} 현재값 = ${STOCK_AFTER} (시드 초기값 = 10)"

if [[ -n "${STOCK_AFTER}" && "${STOCK_AFTER}" -lt 10 ]]; then
  pass "Redis stock DECR 확인: stock:${TEST_PRODUCT_ID} = ${STOCK_AFTER} (< 10)"
elif [[ "${STOCK_AFTER}" == "10" ]]; then
  echo -e "${YELLOW}[WARN]${NC} Redis stock DECR: stock:${TEST_PRODUCT_ID} = 10 (미감소) — 성공 경로 결제가 아직 처리 중이거나 PG Sandbox 미연결"
  FAIL_COUNT=$((FAIL_COUNT + 1))
  FAIL_ITEMS+=("Redis DECR: stock:${TEST_PRODUCT_ID} 미감소")
else
  fail "Redis DECR 확인" "stock:${TEST_PRODUCT_ID} = ${STOCK_AFTER:-없음}"
fi

# ─────────────────────────────────────────────
# 8. Reconciler trigger 후 Redis↔RDB 발산 없음
# ─────────────────────────────────────────────
section "8. Reconciler trigger — Redis↔RDB 발산 확인"

# PaymentReconciler는 @Scheduled(fixedDelayString="reconciler.fixed-delay-ms:120000") 자동 실행.
# 즉각 트리거 엔드포인트가 없으므로 현재 발산 카운트를 메트릭에서 읽는다.
METRICS_RESP=$(curl -sf "${PAYMENT_SERVICE_BASE}/actuator/prometheus" 2>/dev/null || echo "")

if [[ -n "${METRICS_RESP}" ]]; then
  pass "payment-service /actuator/prometheus 응답 수신"

  # payment.stock_cache.divergence_count 현재값 확인
  DIVERGENCE_VAL=$(echo "${METRICS_RESP}" \
    | grep '^payment_stock_cache_divergence_count_total' \
    | awk '{print $2}' | head -1 || echo "")

  if [[ -z "${DIVERGENCE_VAL}" || "${DIVERGENCE_VAL}" == "0.0" || "${DIVERGENCE_VAL}" == "0" ]]; then
    pass "Redis↔RDB 발산 카운트 = ${DIVERGENCE_VAL:-0} (발산 없음)"
  else
    fail "Redis↔RDB 발산 카운트" "${DIVERGENCE_VAL}건 발산 감지 — PaymentReconciler 로그 확인"
  fi
else
  fail "payment-service /actuator/prometheus" "curl 실패 — actuator.prometheus 미노출 확인"
fi

# ─────────────────────────────────────────────
# 9. 메트릭 scraping
# ─────────────────────────────────────────────
section "9. 메트릭 scraping"

if [[ -n "${METRICS_RESP}" ]]; then
  # 9a. payment.outbox.pending_age_seconds 존재 확인
  if echo "${METRICS_RESP}" | grep -q 'payment_outbox_pending_age_seconds'; then
    pass "메트릭 존재: payment.outbox.pending_age_seconds"
  else
    fail "메트릭 존재: payment.outbox.pending_age_seconds" "prometheus 출력에서 미발견"
  fi

  # 9b. payment.stock_cache.divergence_count 존재 확인
  if echo "${METRICS_RESP}" | grep -q 'payment_stock_cache_divergence_count'; then
    pass "메트릭 존재: payment.stock_cache.divergence_count"
  else
    fail "메트릭 존재: payment.stock_cache.divergence_count" "prometheus 출력에서 미발견"
  fi
else
  fail "메트릭 scraping" "/actuator/prometheus 응답 없음"
fi

# ─────────────────────────────────────────────
# 10. PgStatusPort 부재 확인 (불변식 19)
# ─────────────────────────────────────────────
section "10. PgStatusPort 부재 확인 (불변식 19)"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PG_STATUS_FOUND=$(grep -rn "PgStatusPort\|PgStatusHttpAdapter" \
  "${REPO_ROOT}/payment-service/src/main/" 2>/dev/null || echo "NOT_FOUND")

if [[ "${PG_STATUS_FOUND}" == "NOT_FOUND" || -z "${PG_STATUS_FOUND}" ]]; then
  pass "PgStatusPort 부재 확인: payment-service/src/main/ 에 PgStatusPort 없음 (불변식 19 충족)"
else
  fail "PgStatusPort 부재 확인" "발견됨 — 불변식 19 위반:\n${PG_STATUS_FOUND}"
fi

# ─────────────────────────────────────────────
# 11. 테스트 데이터 정리
# ─────────────────────────────────────────────
section "11. 테스트 데이터 정리"

payment_db_query "
DELETE po FROM payment_order po
  JOIN payment_event pe ON po.payment_event_id = pe.id
 WHERE pe.order_id IN (
   '${ORDER_SUCCESS}', '${ORDER_FAILED}', '${ORDER_QUARANTINE}'
 );
" 2>/dev/null

payment_db_query "
DELETE FROM payment_event
 WHERE order_id IN (
   '${ORDER_SUCCESS}', '${ORDER_FAILED}', '${ORDER_QUARANTINE}'
 );
" 2>/dev/null

docker exec "${REDIS_CONTAINER}" redis-cli DEL "stock:${TEST_PRODUCT_ID}" >/dev/null 2>&1 || true
pass "테스트 데이터 정리 완료"

# ─────────────────────────────────────────────
# 최종 요약
# ─────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════"
echo " Phase 1 Gate 결과"
echo "════════════════════════════════════════════"
echo -e " PASS: ${GREEN}${PASS_COUNT}${NC}"
echo -e " FAIL: ${RED}${FAIL_COUNT}${NC}"

if [[ ${FAIL_COUNT} -gt 0 ]]; then
  echo ""
  echo -e "${RED}실패 항목:${NC}"
  for item in "${FAIL_ITEMS[@]}"; do
    echo "  - ${item}"
  done
  echo ""
  echo -e "${RED}[GATE FAIL]${NC} Phase 1 재수정 후 재실행 필요."
  exit 1
else
  echo ""
  echo -e "${GREEN}[GATE PASS]${NC} Phase 2.a 진입 가능."
  exit 0
fi
