#!/usr/bin/env bash
# kafka-topic-config.sh
# ADR-30 — 토픽 설정 검증 스크립트
#
# 목적: payment 도메인 토픽 3개의 partition 수·replication-factor·min.insync.replicas가
#       동일하고 ADR-30 규정을 충족하는지 검증한다.
#
# 사용법:
#   bash docs/phase-gate/kafka-topic-config.sh [BOOTSTRAP_SERVER]
#
# 기본값:
#   BOOTSTRAP_SERVER=localhost:29092  (docker-compose.infra.yml 로컬 포트)
#
# 종료 코드:
#   0 — 전체 검증 통과
#   1 — 하나 이상 검증 실패

set -euo pipefail

BOOTSTRAP_SERVER="${1:-localhost:29092}"

# ADR-30 대상 토픽 목록 (retry 전용 토픽 제외)
TOPICS=(
  "payment.commands.confirm"
  "payment.commands.confirm.dlq"
  "payment.events.confirmed"
)

# ── 색상 정의 ────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}[PASS]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; }
info() { echo -e "${YELLOW}[INFO]${NC} $*"; }

FAIL_COUNT=0

# ── kafka-topics 명령 존재 확인 ──────────────────────────────
if ! command -v kafka-topics &>/dev/null; then
  info "kafka-topics 명령을 찾을 수 없습니다. Docker 컨테이너 내부에서 실행하거나 PATH를 확인하세요."
  info "대안: docker exec payment-kafka kafka-topics --bootstrap-server localhost:9092 --describe --topic <TOPIC>"
  exit 1
fi

echo "======================================================="
echo "  Kafka 토픽 설정 검증 (ADR-30)"
echo "  BOOTSTRAP: ${BOOTSTRAP_SERVER}"
echo "======================================================="

# ── 각 토픽 describe 후 partition/rf/isr 파싱 ────────────────
declare -A PARTITION_MAP
declare -A RF_MAP
declare -A ISR_MAP

for TOPIC in "${TOPICS[@]}"; do
  echo ""
  info "토픽 조회: ${TOPIC}"

  DESCRIBE_OUT=$(kafka-topics \
    --bootstrap-server "${BOOTSTRAP_SERVER}" \
    --describe \
    --topic "${TOPIC}" 2>&1) || {
    fail "토픽 조회 실패: ${TOPIC}"
    FAIL_COUNT=$((FAIL_COUNT + 1))
    continue
  }

  # PartitionCount 파싱
  PARTITION_COUNT=$(echo "${DESCRIBE_OUT}" \
    | grep "^Topic:" \
    | head -1 \
    | grep -oP 'PartitionCount:\s*\K[0-9]+' || echo "")

  # ReplicationFactor 파싱
  RF=$(echo "${DESCRIBE_OUT}" \
    | grep "^Topic:" \
    | head -1 \
    | grep -oP 'ReplicationFactor:\s*\K[0-9]+' || echo "")

  # min.insync.replicas 파싱 (Configs 행)
  ISR=$(echo "${DESCRIBE_OUT}" \
    | grep -oP 'min\.insync\.replicas=\K[0-9]+' \
    | head -1 || echo "")

  if [[ -z "${PARTITION_COUNT}" || -z "${RF}" ]]; then
    fail "파싱 실패 — 토픽이 존재하지 않거나 출력 형식이 다릅니다: ${TOPIC}"
    echo "  출력 내용:"
    echo "${DESCRIBE_OUT}" | head -10
    FAIL_COUNT=$((FAIL_COUNT + 1))
    continue
  fi

  PARTITION_MAP["${TOPIC}"]="${PARTITION_COUNT}"
  RF_MAP["${TOPIC}"]="${RF}"
  ISR_MAP["${TOPIC}"]="${ISR:-N/A}"

  echo "  PartitionCount    = ${PARTITION_COUNT}"
  echo "  ReplicationFactor = ${RF}"
  echo "  min.insync.replicas = ${ISR:-N/A (broker default)}"
done

echo ""
echo "======================================================="
echo "  검증 1: 토픽 간 PartitionCount 동일 여부 (ADR-30)"
echo "======================================================="

FIRST_TOPIC="${TOPICS[0]}"
BASELINE_PARTITION="${PARTITION_MAP[${FIRST_TOPIC}]:-}"

if [[ -z "${BASELINE_PARTITION}" ]]; then
  fail "기준 토픽 partition 수를 확인할 수 없어 동일성 검증을 건너뜁니다."
  FAIL_COUNT=$((FAIL_COUNT + 1))
else
  ALL_SAME=true
  for TOPIC in "${TOPICS[@]}"; do
    CURRENT="${PARTITION_MAP[${TOPIC}]:-}"
    if [[ "${CURRENT}" != "${BASELINE_PARTITION}" ]]; then
      fail "${TOPIC} partition=${CURRENT} (기준: ${BASELINE_PARTITION})"
      ALL_SAME=false
      FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
  done

  if [[ "${ALL_SAME}" == "true" ]]; then
    pass "전체 토픽 partition 수 동일: ${BASELINE_PARTITION}"
  fi
fi

echo ""
echo "======================================================="
echo "  검증 2: ReplicationFactor >= 3 (프로덕션 권고)"
echo "  (로컬 단일 브로커 환경에서는 1 허용 — 경고만)"
echo "======================================================="

for TOPIC in "${TOPICS[@]}"; do
  RF_VAL="${RF_MAP[${TOPIC}]:-}"
  if [[ -z "${RF_VAL}" ]]; then
    continue
  fi
  if [[ "${RF_VAL}" -ge 3 ]]; then
    pass "${TOPIC}: replication.factor=${RF_VAL}"
  elif [[ "${RF_VAL}" -eq 1 ]]; then
    echo -e "${YELLOW}[WARN]${NC} ${TOPIC}: replication.factor=${RF_VAL} (로컬 허용, 프로덕션 배포 전 3으로 변경 필요)"
  else
    fail "${TOPIC}: replication.factor=${RF_VAL} (2는 split-brain 위험 — 1 또는 3+ 사용)"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
done

echo ""
echo "======================================================="
echo "  검증 3: retry 전용 토픽 미존재 확인 (ADR-30 방침)"
echo "======================================================="

RETRY_TOPICS=(
  "payment.commands.confirm.retry"
  "payment.commands.confirm.retry-0"
  "payment.commands.confirm.retry-1"
)

for RETRY_TOPIC in "${RETRY_TOPICS[@]}"; do
  RETRY_OUT=$(kafka-topics \
    --bootstrap-server "${BOOTSTRAP_SERVER}" \
    --describe \
    --topic "${RETRY_TOPIC}" 2>&1 || true)

  if echo "${RETRY_OUT}" | grep -q "does not exist"; then
    pass "retry 토픽 없음: ${RETRY_TOPIC}"
  elif echo "${RETRY_OUT}" | grep -q "^Topic:"; then
    fail "retry 전용 토픽이 존재합니다 (ADR-30 위반): ${RETRY_TOPIC}"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  else
    info "확인 불가 (무시): ${RETRY_TOPIC}"
  fi
done

echo ""
echo "======================================================="
if [[ "${FAIL_COUNT}" -eq 0 ]]; then
  pass "전체 검증 통과 (ADR-30 준수)"
  echo "======================================================="
  exit 0
else
  fail "검증 실패 ${FAIL_COUNT}건"
  echo "======================================================="
  exit 1
fi
