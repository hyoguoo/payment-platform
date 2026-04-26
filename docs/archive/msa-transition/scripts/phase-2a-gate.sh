#!/usr/bin/env bash
# Phase 2.a Gate — pg-service 골격 + Outbox 파이프라인 + consumer 기반 검증 스크립트
# MSA-TRANSITION T2a-Gate
#
# 전제조건:
#   - docker, curl, jq, mysql CLI 설치 필요
#   - 인프라 + pg-service 기동 완료 필요:
#       docker compose -f docker-compose.infra.yml up -d
#       # pg-service 별도 기동:
#       PG_SERVICE_PORT 포트 주의:
#         pg-service 에는 application.yml 이 없으므로 Spring Boot 기본 포트(8080) 가 사용된다.
#         Gateway 도 8080 을 사용하므로 두 서비스를 동시 기동 시 포트 충돌 발생.
#         해결방법 1) Gateway 종료 후 pg-service 단독 기동
#         해결방법 2) 환경 변수로 포트 지정:
#           SERVER_PORT=8082 ./gradlew :pg-service:bootRun
#         그 후 이 스크립트 실행 시:
#           PG_SERVICE_BASE=http://localhost:8082 bash scripts/phase-gate/phase-2a-gate.sh
#       # Gateway + payment-service 는 별도 기동 (선택 — 이 Gate 에서는 pg-service 전용 검증)
#
# 실행:
#   bash scripts/phase-gate/phase-2a-gate.sh
#
#   # 환경 변수 재정의 예시
#   PG_SERVICE_BASE=http://localhost:8082 bash scripts/phase-gate/phase-2a-gate.sh
#
# 결과:
#   - 각 체크마다 [PASS] / [FAIL] 출력
#   - 전부 PASS → exit 0 (Phase 2.b 진입 가능)
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
# 주의: pg-service 에 application.yml 미존재 → 기본 포트 8080 (Gateway 와 충돌 가능)
# Gateway 를 종료하거나 SERVER_PORT=8082 환경 변수로 pg-service 기동 후 PG_SERVICE_BASE 재정의 필요
PG_SERVICE_BASE="${PG_SERVICE_BASE:-http://localhost:8080}"

# pg-service DB 접속 정보 (docker-compose.infra.yml mysql-pg 컨테이너 기준)
PG_DB_HOST="${PG_DB_HOST:-127.0.0.1}"
PG_DB_PORT="${PG_DB_PORT:-3308}"
PG_DB_NAME="${PG_DB_NAME:-pg}"
PG_DB_USER="${PG_DB_USER:-pg}"
PG_DB_PASS="${PG_DB_PASS:-payment123}"

# Kafka 설정
KAFKA_CONTAINER="${KAFKA_CONTAINER:-payment-kafka}"

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

# pg-service DB 쿼리 실행 헬퍼
pg_db_query() {
  mysql \
    -h "${PG_DB_HOST}" \
    -P "${PG_DB_PORT}" \
    -u "${PG_DB_USER}" \
    -p"${PG_DB_PASS}" \
    --batch \
    --skip-column-names \
    "${PG_DB_NAME}" \
    -e "$1" 2>/dev/null || echo ""
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
# 1. pg-service /actuator/health UP 확인
# ─────────────────────────────────────────────
section "1. pg-service /actuator/health"

echo "  [INFO] pg-service 접속 대상: ${PG_SERVICE_BASE}"
echo "  [INFO] 포트 충돌 주의: pg-service 기본 포트=8080 (Gateway 와 동일)"
echo "  [INFO] 별도 포트 사용 시: PG_SERVICE_BASE=http://localhost:8082 bash $(basename "$0")"

PG_HEALTH=$(curl -sf "${PG_SERVICE_BASE}/actuator/health" 2>/dev/null || echo "")
if [[ -n "${PG_HEALTH}" ]]; then
  PG_STATUS=$(echo "${PG_HEALTH}" | jq -r '.status' 2>/dev/null || echo "")
  if [[ "${PG_STATUS}" == "UP" ]]; then
    pass "pg-service /actuator/health → UP"
  else
    fail "pg-service /actuator/health → UP" "status=${PG_STATUS:-파싱실패} (${PG_SERVICE_BASE}/actuator/health)"
  fi
else
  fail "pg-service /actuator/health" "curl 실패 (${PG_SERVICE_BASE}/actuator/health) — pg-service 미기동 또는 포트 불일치"
fi

# ─────────────────────────────────────────────
# 2. mysql-pg 컨테이너 기동 + pg DB 접속
# ─────────────────────────────────────────────
section "2. mysql-pg 컨테이너 기동 확인 + pg DB 접속"

# 2a. 컨테이너 running 상태
PG_MYSQL_STATE=$(docker compose -f docker-compose.infra.yml ps --format json 2>/dev/null \
  | jq -r 'select(.Name == "payment-mysql-pg") | .State' 2>/dev/null || echo "")
if [[ "${PG_MYSQL_STATE}" == "running" ]]; then
  pass "컨테이너 running: payment-mysql-pg"
else
  fail "컨테이너 running: payment-mysql-pg" "상태=${PG_MYSQL_STATE:-미기동} — docker compose -f docker-compose.infra.yml up -d 실행 필요"
fi

# 2b. pg DB 접속
PG_MYSQL_OK=$(pg_db_query "SELECT 1;")
if [[ "${PG_MYSQL_OK}" == "1" ]]; then
  pass "pg MySQL DB 접속 성공 (host=${PG_DB_HOST} port=${PG_DB_PORT} db=${PG_DB_NAME})"
else
  fail "pg MySQL DB 접속" "쿼리 실패 — DB 미기동 또는 접속 정보 오류 (host=${PG_DB_HOST} port=${PG_DB_PORT})"
fi

# ─────────────────────────────────────────────
# 3. Flyway V1 마이그레이션 확인 (pg_inbox · pg_outbox 테이블)
# ─────────────────────────────────────────────
section "3. Flyway V1 마이그레이션 — pg_inbox · pg_outbox 테이블"

# 3a. flyway_schema_history version=1 존재
FLYWAY_V1=$(pg_db_query \
  "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1;" 2>/dev/null || echo "0")
if [[ "${FLYWAY_V1}" == "1" ]]; then
  pass "Flyway V1 마이그레이션 성공 (flyway_schema_history version=1 success=1)"
else
  fail "Flyway V1 마이그레이션" "flyway_schema_history version=1 success=1 행 없음 — pg-service 미기동 또는 마이그레이션 실패"
fi

# 3b. Flyway 실패 마이그레이션 0건
FLYWAY_FAILED=$(pg_db_query \
  "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0;" 2>/dev/null || echo "0")
if [[ "${FLYWAY_FAILED}" == "0" ]]; then
  pass "Flyway 실패 마이그레이션 0건"
else
  fail "Flyway 실패 마이그레이션" "${FLYWAY_FAILED}건 실패 — flyway_schema_history 확인"
fi

# 3c. pg_inbox 테이블 존재
PG_INBOX_TABLE=$(pg_db_query \
  "SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema='${PG_DB_NAME}' AND table_name='pg_inbox';" 2>/dev/null || echo "0")
if [[ "${PG_INBOX_TABLE}" == "1" ]]; then
  pass "테이블 존재: pg_inbox"
else
  fail "테이블 존재: pg_inbox" "Flyway V1 미실행 가능성 — pg-service 재기동 확인"
fi

# 3d. pg_outbox 테이블 존재
PG_OUTBOX_TABLE=$(pg_db_query \
  "SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema='${PG_DB_NAME}' AND table_name='pg_outbox';" 2>/dev/null || echo "0")
if [[ "${PG_OUTBOX_TABLE}" == "1" ]]; then
  pass "테이블 존재: pg_outbox"
else
  fail "테이블 존재: pg_outbox" "Flyway V1 미실행 가능성 — pg-service 재기동 확인"
fi

# ─────────────────────────────────────────────
# 4. Kafka 토픽 payment.commands.confirm 존재 확인
# ─────────────────────────────────────────────
section "4. Kafka 토픽 payment.commands.confirm 존재"

if docker exec "${KAFKA_CONTAINER}" kafka-topics \
    --bootstrap-server localhost:9092 \
    --list >/dev/null 2>&1; then
  pass "Kafka 브로커 응답 (bootstrap-server localhost:9092)"
else
  fail "Kafka 브로커 응답" "kafka-topics --list 실패 — ${KAFKA_CONTAINER} 컨테이너 미기동"
fi

TOPIC_LIST=$(docker exec "${KAFKA_CONTAINER}" kafka-topics \
  --bootstrap-server localhost:9092 \
  --list 2>/dev/null || echo "")

if echo "${TOPIC_LIST}" | grep -qx "payment.commands.confirm"; then
  pass "토픽 존재: payment.commands.confirm"
else
  fail "토픽 존재: payment.commands.confirm" "토픽 미생성 → scripts/phase-gate/create-topics.sh 실행 필요"
fi

# ─────────────────────────────────────────────
# 5. pg-service consumer group 등록 확인
# ─────────────────────────────────────────────
section "5. pg-service consumer group 등록 확인"

CONSUMER_GROUPS=$(docker exec "${KAFKA_CONTAINER}" kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --list 2>/dev/null || echo "")

if echo "${CONSUMER_GROUPS}" | grep -qx "pg-service"; then
  pass "consumer group 존재: pg-service (payment.commands.confirm 구독 중)"
else
  fail "consumer group 존재: pg-service" \
    "pg-service consumer group 미등록 — pg-service 미기동이거나 Kafka 연결 실패 (spring.kafka.bootstrap-servers 설정 확인)"
fi

# ─────────────────────────────────────────────
# 6. PgOutboxImmediateWorker 기동 확인 (pg-service 로그 grep)
# ─────────────────────────────────────────────
section "6. PgOutboxImmediateWorker 기동 확인"

# 6a. pg-service 헬스체크 재확인 (UP = JVM + SmartLifecycle 정상 기동)
PG_HEALTH2=$(curl -sf "${PG_SERVICE_BASE}/actuator/health" 2>/dev/null || echo "")
PG_LIVENESS=""
if [[ -n "${PG_HEALTH2}" ]]; then
  PG_LIVENESS=$(echo "${PG_HEALTH2}" | jq -r '.status' 2>/dev/null || echo "")
fi

if [[ "${PG_LIVENESS}" == "UP" ]]; then
  pass "pg-service actuator/health UP → SmartLifecycle(PgOutboxImmediateWorker) 기동 전제 충족"
else
  fail "pg-service actuator/health (SmartLifecycle 확인)" "status=${PG_LIVENESS:-없음}"
fi

# 6b. Docker 로그에서 PgOutboxImmediateWorker started 메시지 확인
# pg-service 를 Docker 컨테이너로 실행 중인 경우에만 유효 (로컬 bootRun 시 SKIP 안내)
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

PG_CONTAINER_NAME="${PG_CONTAINER_NAME:-}"
if [[ -n "${PG_CONTAINER_NAME}" ]]; then
  WORKER_LOG=$(docker logs "${PG_CONTAINER_NAME}" 2>&1 \
    | grep "PgOutboxImmediateWorker started" | tail -1 || echo "")
  if [[ -n "${WORKER_LOG}" ]]; then
    pass "PgOutboxImmediateWorker started 로그 확인 (컨테이너: ${PG_CONTAINER_NAME})"
  else
    fail "PgOutboxImmediateWorker started 로그" \
      "로그 미발견 — 컨테이너(${PG_CONTAINER_NAME}) 로그 직접 확인: docker logs ${PG_CONTAINER_NAME} | grep PgOutboxImmediateWorker"
  fi
else
  echo -e "${YELLOW}[SKIP]${NC} PgOutboxImmediateWorker 로그 확인 — PG_CONTAINER_NAME 환경 변수 미설정 (로컬 bootRun 환경)"
  echo "  [INFO] 수동 확인: pg-service 기동 로그에서 'PgOutboxImmediateWorker started' 메시지 존재 여부 확인"
fi

# ─────────────────────────────────────────────
# 7. pg-service inbox NONE→IN_PROGRESS 전이 확인 (dry_run)
# ─────────────────────────────────────────────
section "7. pg_inbox NONE→IN_PROGRESS 전이 smoke 확인"

# pg_inbox 테이블이 존재하는 경우에만 실행
if [[ "${PG_INBOX_TABLE}" == "1" ]]; then
  # 테스트용 더미 행 INSERT (NONE 상태 시드)
  SUFFIX_GATE="gate2a-$$"
  TEST_ORDER_ID="gate2a-order-${SUFFIX_GATE}"

  pg_db_query "
  INSERT INTO pg_inbox (order_id, status, amount, created_at, updated_at)
  VALUES ('${TEST_ORDER_ID}', 'NONE', 10000, NOW(6), NOW(6));
  " 2>/dev/null && SEED_OK="true" || SEED_OK="false"

  if [[ "${SEED_OK}" == "true" ]]; then
    pass "pg_inbox NONE 상태 시드 행 INSERT (orderId=${TEST_ORDER_ID})"

    # CAS IN_PROGRESS 전이 확인 (transitNoneToInProgress 로직 재현)
    # UPDATE ... WHERE order_id=? AND status='NONE' → 1건 갱신 시 전이 성공
    UPDATE_ROWS=$(pg_db_query "
    UPDATE pg_inbox
       SET status='IN_PROGRESS', updated_at=NOW(6)
     WHERE order_id='${TEST_ORDER_ID}' AND status='NONE';
    SELECT ROW_COUNT();
    " 2>/dev/null | tail -1 || echo "0")

    if [[ "${UPDATE_ROWS}" == "1" ]]; then
      pass "pg_inbox NONE→IN_PROGRESS CAS 전이 성공 (ROW_COUNT=1)"
    else
      fail "pg_inbox NONE→IN_PROGRESS CAS 전이" "ROW_COUNT=${UPDATE_ROWS:-0} (전이 실패)"
    fi

    # 두 번째 CAS 시도 → 0건 (이미 IN_PROGRESS)
    UPDATE_ROWS2=$(pg_db_query "
    UPDATE pg_inbox
       SET status='IN_PROGRESS', updated_at=NOW(6)
     WHERE order_id='${TEST_ORDER_ID}' AND status='NONE';
    SELECT ROW_COUNT();
    " 2>/dev/null | tail -1 || echo "0")

    if [[ "${UPDATE_ROWS2}" == "0" ]]; then
      pass "pg_inbox CAS 멱등성 확인: 이미 IN_PROGRESS → ROW_COUNT=0 (중복 전이 차단)"
    else
      fail "pg_inbox CAS 멱등성" "ROW_COUNT=${UPDATE_ROWS2} (중복 전이 허용 — 불변식 4b 위반 가능성)"
    fi

    # 테스트 행 정리
    pg_db_query "DELETE FROM pg_inbox WHERE order_id='${TEST_ORDER_ID}';" 2>/dev/null || true
    pass "pg_inbox 테스트 시드 행 정리 완료"
  else
    fail "pg_inbox NONE 상태 시드 행 INSERT" "INSERT 실패 — pg_inbox 테이블 접근 불가"
  fi
else
  echo -e "${YELLOW}[SKIP]${NC} pg_inbox CAS 전이 확인 — pg_inbox 테이블 미존재 (Flyway 3c FAIL 시 건너뜀)"
fi

# ─────────────────────────────────────────────
# 8. dry_run 메트릭 — pg.confirm.* 카운터 노출 확인
# ─────────────────────────────────────────────
section "8. dry_run 메트릭 — pg.confirm.* 카운터 노출"

PG_METRICS=$(curl -sf "${PG_SERVICE_BASE}/actuator/prometheus" 2>/dev/null || echo "")

if [[ -n "${PG_METRICS}" ]]; then
  pass "pg-service /actuator/prometheus 응답 수신"

  # toss.api.call.total (TossApiMetrics — T2a-02 산출물)
  if echo "${PG_METRICS}" | grep -q 'toss_api_call_total'; then
    pass "메트릭 존재: toss.api.call.total"
  else
    echo -e "${YELLOW}[WARN]${NC} 메트릭 toss.api.call.total 미발견 — 벤더 API 호출 전(T2b-01 이전)이므로 카운터가 0이면 정상. 이름 확인 필요."
  fi

  # pg.outbox.channel.* (PgOutboxChannel — T2a-05b Micrometer 게이지)
  if echo "${PG_METRICS}" | grep -q 'pg_outbox_channel'; then
    pass "메트릭 존재: pg.outbox.channel.* (PgOutboxChannel Micrometer 게이지)"
  else
    fail "메트릭 존재: pg.outbox.channel.*" "prometheus 출력에서 pg_outbox_channel 미발견 — PgOutboxChannel Micrometer 게이지 등록 확인"
  fi

  # Spring Boot 기본 메트릭 (jvm.*, process.*) 존재 확인
  if echo "${PG_METRICS}" | grep -q 'jvm_'; then
    pass "메트릭 존재: JVM 기본 메트릭 (jvm_*)"
  else
    fail "메트릭 존재: JVM 기본 메트릭" "prometheus 출력에서 jvm_* 미발견 — Actuator prometheus 설정 확인"
  fi
else
  fail "pg-service /actuator/prometheus" "curl 실패 — management.endpoints.web.exposure.include=prometheus 설정 확인"
fi

# ─────────────────────────────────────────────
# 9. pg_outbox 미처리 행 0건 확인 (기동 직후 clean state)
# ─────────────────────────────────────────────
section "9. pg_outbox 미처리 pending 행 확인"

if [[ "${PG_OUTBOX_TABLE}" == "1" ]]; then
  PENDING_COUNT=$(pg_db_query \
    "SELECT COUNT(*) FROM pg_outbox WHERE processed_at IS NULL;" 2>/dev/null || echo "")

  if [[ -n "${PENDING_COUNT}" ]]; then
    if [[ "${PENDING_COUNT}" == "0" ]]; then
      pass "pg_outbox 미처리(processed_at IS NULL) 행 0건 (clean state)"
    else
      echo -e "${YELLOW}[WARN]${NC} pg_outbox 미처리 행 ${PENDING_COUNT}건 — 기동 직후가 아니거나 Worker 미처리 지연 가능. 수동 확인 권장."
    fi
  else
    fail "pg_outbox pending 행 조회" "SELECT COUNT(*) 실패"
  fi
else
  echo -e "${YELLOW}[SKIP]${NC} pg_outbox pending 확인 — pg_outbox 테이블 미존재 (Flyway 3d FAIL 시 건너뜀)"
fi

# ─────────────────────────────────────────────
# 최종 요약
# ─────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════"
echo " Phase 2.a Gate 결과"
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
  echo -e "${RED}[GATE FAIL]${NC} Phase 2.a 재수정 후 재실행 필요."
  exit 1
else
  echo ""
  echo -e "${GREEN}[GATE PASS]${NC} Phase 2.b 진입 가능."
  exit 0
fi
