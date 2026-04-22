#!/usr/bin/env bash
# Phase 0 Gate — 인프라 기반 smoke 검증 스크립트
# MSA-TRANSITION T0-Gate
#
# 전제조건:
#   - docker, curl, jq 설치 필요
#   - 이 스크립트 실행 전 반드시 compose up 완료 필요:
#       docker compose -f docker-compose.infra.yml up -d
#
# 실행:
#   bash scripts/phase-gate/phase-0-gate.sh
#
# 결과:
#   - 각 체크마다 [PASS] / [FAIL] 출력
#   - 전부 PASS → exit 0 (Phase 1 진입 가능)
#   - 하나라도 FAIL → exit 1 (Phase 0 재수정 루프)

set -euo pipefail

# ─────────────────────────────────────────────
# 색상/상수
# ─────────────────────────────────────────────
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

PASS_COUNT=0
FAIL_COUNT=0
FAIL_ITEMS=()

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

# ─────────────────────────────────────────────
# 전제조건 확인 (docker / curl / jq)
# ─────────────────────────────────────────────
section "전제조건 확인"

for cmd in docker curl jq; do
  if command -v "${cmd}" >/dev/null 2>&1; then
    pass "명령어 존재: ${cmd}"
  else
    fail "명령어 존재: ${cmd}" "${cmd} 미설치"
  fi
done

# ─────────────────────────────────────────────
# 1. 인프라 컴포즈 기동 여부
# ─────────────────────────────────────────────
section "1. 인프라 컴포즈 컨테이너 기동 상태"

REQUIRED_CONTAINERS=(
  payment-kafka
  payment-redis-dedupe
  payment-redis-stock
  payment-mysql-payment
  payment-eureka
)

for container in "${REQUIRED_CONTAINERS[@]}"; do
  status=$(docker compose -f docker-compose.infra.yml ps --format json 2>/dev/null \
    | jq -r --arg name "${container}" 'select(.Name == $name) | .State' 2>/dev/null || echo "")
  if [[ "${status}" == "running" ]]; then
    pass "컨테이너 running: ${container}"
  else
    fail "컨테이너 running: ${container}" "상태=${status:-미기동}"
  fi
done

# ─────────────────────────────────────────────
# 2. Kafka healthcheck — topic list 성공
# ─────────────────────────────────────────────
section "2. Kafka healthcheck"

if docker exec payment-kafka kafka-topics \
    --bootstrap-server localhost:9092 \
    --list >/dev/null 2>&1; then
  pass "Kafka 브로커 응답 (bootstrap-server localhost:9092)"
else
  fail "Kafka 브로커 응답 (bootstrap-server localhost:9092)" "kafka-topics --list 실패"
fi

# ─────────────────────────────────────────────
# 3. Kafka 토픽 3개 존재 확인
# ─────────────────────────────────────────────
section "3. Kafka 토픽 존재 확인"

REQUIRED_TOPICS=(
  "payment.commands.confirm"
  "payment.commands.confirm.dlq"
  "payment.events.confirmed"
)

TOPIC_LIST=$(docker exec payment-kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --list 2>/dev/null || echo "")

for topic in "${REQUIRED_TOPICS[@]}"; do
  if echo "${TOPIC_LIST}" | grep -qx "${topic}"; then
    pass "토픽 존재: ${topic}"
  else
    fail "토픽 존재: ${topic}" "토픽 미생성 → T0-01b 스크립트(create-topics.sh) 재실행 필요"
  fi
done

# ─────────────────────────────────────────────
# 4. Kafka 토픽 파티션 수 동일 확인 (ADR-30)
# ─────────────────────────────────────────────
section "4. Kafka 토픽 파티션 수 동일 (ADR-30)"

PARTITION_COUNTS=()
PARTITION_CHECK_OK=true

for topic in "${REQUIRED_TOPICS[@]}"; do
  count=$(docker exec payment-kafka kafka-topics \
    --bootstrap-server localhost:9092 \
    --describe \
    --topic "${topic}" 2>/dev/null \
    | grep -E "^Topic:" \
    | awk -F'\t' '{for(i=1;i<=NF;i++) if($i~/^PartitionCount:/) {sub(/^PartitionCount:/,"",$i); print $i}}' \
    | head -1 || echo "")

  if [[ -n "${count}" ]]; then
    PARTITION_COUNTS+=("${count}")
    pass "파티션 수 확인: ${topic} = ${count}"
  else
    fail "파티션 수 확인: ${topic}" "토픽 미존재 또는 describe 실패"
    PARTITION_CHECK_OK=false
  fi
done

if [[ "${PARTITION_CHECK_OK}" == "true" && ${#PARTITION_COUNTS[@]} -gt 0 ]]; then
  FIRST_COUNT="${PARTITION_COUNTS[0]}"
  ALL_SAME=true
  for count in "${PARTITION_COUNTS[@]}"; do
    if [[ "${count}" != "${FIRST_COUNT}" ]]; then
      ALL_SAME=false
      break
    fi
  done
  if [[ "${ALL_SAME}" == "true" ]]; then
    pass "토픽 파티션 수 동일: 모두 ${FIRST_COUNT}"
  else
    fail "토픽 파티션 수 동일" "파티션 수 불일치: ${PARTITION_COUNTS[*]}"
  fi
fi

# ─────────────────────────────────────────────
# 5. Dedupe Redis (6379) PING
# ─────────────────────────────────────────────
section "5. Dedupe Redis (6379) healthcheck"

DEDUPE_PING=$(docker exec payment-redis-dedupe redis-cli ping 2>/dev/null || echo "")
if [[ "${DEDUPE_PING}" == "PONG" ]]; then
  pass "Dedupe Redis PING → PONG"
else
  fail "Dedupe Redis PING" "응답=${DEDUPE_PING:-없음}"
fi

# ─────────────────────────────────────────────
# 6. Stock Redis (6380) PING + AOF 설정 확인
# ─────────────────────────────────────────────
section "6. Stock Redis (6380) healthcheck + AOF 확인"

STOCK_PING=$(docker exec payment-redis-stock redis-cli ping 2>/dev/null || echo "")
if [[ "${STOCK_PING}" == "PONG" ]]; then
  pass "Stock Redis PING → PONG"
else
  fail "Stock Redis PING" "응답=${STOCK_PING:-없음}"
fi

AOF_VALUE=$(docker exec payment-redis-stock redis-cli CONFIG GET appendonly 2>/dev/null \
  | tail -1 || echo "")
if [[ "${AOF_VALUE}" == "yes" ]]; then
  pass "Stock Redis AOF 활성화 (appendonly=yes)"
else
  fail "Stock Redis AOF 활성화" "appendonly=${AOF_VALUE:-미확인}"
fi

# ─────────────────────────────────────────────
# 7. Redis SETNX 원자성 확인 (멱등성 키 패턴)
# ─────────────────────────────────────────────
section "7. Stock Redis SETNX 원자성"

TEST_KEY="gate-test:phase-0:$$"

# 첫 번째 SET NX → OK
SET1=$(docker exec payment-redis-stock redis-cli SET "${TEST_KEY}" 1 NX EX 30 2>/dev/null || echo "")
if [[ "${SET1}" == "OK" ]]; then
  pass "SETNX 첫 번째 → OK"
else
  fail "SETNX 첫 번째" "응답=${SET1:-없음}"
fi

# 두 번째 SET NX → (nil) (이미 존재하므로)
SET2=$(docker exec payment-redis-stock redis-cli SET "${TEST_KEY}" 2 NX EX 30 2>/dev/null || echo "")
if [[ "${SET2}" == "" ]]; then
  pass "SETNX 두 번째 → (nil) (원자성 보장)"
else
  fail "SETNX 두 번째 (nil) 기대" "응답=${SET2}"
fi

# 테스트 키 정리
docker exec payment-redis-stock redis-cli DEL "${TEST_KEY}" >/dev/null 2>&1 || true
pass "SETNX 테스트 키 정리 완료"

# ─────────────────────────────────────────────
# 8. MySQL (payment) healthcheck
# ─────────────────────────────────────────────
section "8. MySQL (payment) healthcheck"

if docker exec payment-mysql-payment mysqladmin ping -h localhost --silent 2>/dev/null; then
  pass "MySQL mysqladmin ping 성공"
else
  fail "MySQL mysqladmin ping" "mysqladmin ping 실패"
fi

# ─────────────────────────────────────────────
# 9. Eureka /actuator/health
# ─────────────────────────────────────────────
section "9. Eureka /actuator/health"

EUREKA_HEALTH=$(curl -sf http://localhost:8761/actuator/health 2>/dev/null || echo "")
if [[ -n "${EUREKA_HEALTH}" ]]; then
  EUREKA_STATUS=$(echo "${EUREKA_HEALTH}" | jq -r '.status' 2>/dev/null || echo "")
  if [[ "${EUREKA_STATUS}" == "UP" ]]; then
    pass "Eureka actuator health → UP"
  else
    fail "Eureka actuator health → UP" "status=${EUREKA_STATUS:-파싱실패}"
  fi
else
  fail "Eureka actuator health" "curl 실패 (http://localhost:8761/actuator/health)"
fi

# ─────────────────────────────────────────────
# 10. Toxiproxy /proxies — 3개 키 존재
# ─────────────────────────────────────────────
section "10. Toxiproxy /proxies"

TOXIPROXY_RESP=$(curl -sf http://localhost:8474/proxies 2>/dev/null || echo "")
if [[ -n "${TOXIPROXY_RESP}" ]]; then
  pass "Toxiproxy /proxies 200 응답"

  REQUIRED_PROXIES=(kafka-proxy mysql-payment-proxy redis-stock-proxy)
  for proxy in "${REQUIRED_PROXIES[@]}"; do
    KEY_EXISTS=$(echo "${TOXIPROXY_RESP}" | jq -r --arg key "${proxy}" 'has($key)' 2>/dev/null || echo "false")
    if [[ "${KEY_EXISTS}" == "true" ]]; then
      pass "Toxiproxy proxy 존재: ${proxy}"
    else
      fail "Toxiproxy proxy 존재: ${proxy}" "응답에 키 없음"
    fi
  done
else
  fail "Toxiproxy /proxies 200 응답" "curl 실패 (http://localhost:8474/proxies)"
fi

# ─────────────────────────────────────────────
# 최종 요약
# ─────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════"
echo " Phase 0 Gate 결과"
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
  echo -e "${RED}[GATE FAIL]${NC} Phase 0 재수정 후 재실행 필요."
  exit 1
else
  echo ""
  echo -e "${GREEN}[GATE PASS]${NC} Phase 1 진입 가능."
  exit 0
fi
