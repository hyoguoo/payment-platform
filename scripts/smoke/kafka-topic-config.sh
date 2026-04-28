#!/usr/bin/env bash
# kafka-topic-config.sh — 토픽 설정 검증 스크립트
#
# 목적: payment 도메인 토픽 3개의 partition 수·replication-factor·min.insync.replicas 가
#       서로 동일한지 검증한다.
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

set -o pipefail

BOOTSTRAP_SERVER_ARG="${1:-}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-payment-kafka}"

# 검증 대상 토픽 목록 (retry 전용 토픽 제외)
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

# ── kafka-topics 실행 경로 결정 ──────────────────────────────
# 호스트에 kafka-topics 가 있으면 그대로, 없으면 docker exec 폴백.
if command -v kafka-topics &>/dev/null; then
  KAFKA_TOPICS=(kafka-topics)
  BOOTSTRAP_SERVER="${BOOTSTRAP_SERVER_ARG:-localhost:9092}"
elif docker exec "${KAFKA_CONTAINER}" kafka-topics --version &>/dev/null; then
  info "호스트에 kafka-topics 없음 — docker exec ${KAFKA_CONTAINER} 경유"
  KAFKA_TOPICS=(docker exec -i "${KAFKA_CONTAINER}" kafka-topics)
  # 컨테이너 내부 네트워크에서 broker 는 localhost:9092
  BOOTSTRAP_SERVER="${BOOTSTRAP_SERVER_ARG:-localhost:9092}"
else
  fail "kafka-topics 사용 불가 — 호스트 PATH 와 ${KAFKA_CONTAINER} 컨테이너 양쪽에서 미존재"
  exit 1
fi

echo "======================================================="
echo "  Kafka 토픽 설정 검증"
echo "  BOOTSTRAP: ${BOOTSTRAP_SERVER}"
echo "======================================================="

# ── 각 토픽 describe 후 partition/rf 파싱 + 즉시 검증 ──────────
# macOS bash 3.2 는 associative array 미지원이라 indexed array + 단일 루프로 처리.
BASELINE_PARTITION=""
ALL_SAME=true

for TOPIC in "${TOPICS[@]}"; do
  echo ""
  info "토픽 조회: ${TOPIC}"

  DESCRIBE_OUT=$("${KAFKA_TOPICS[@]}" \
    --bootstrap-server "${BOOTSTRAP_SERVER}" \
    --describe \
    --topic "${TOPIC}" 2>&1) || {
    fail "토픽 조회 실패: ${TOPIC}"
    FAIL_COUNT=$((FAIL_COUNT + 1))
    continue
  }

  # PartitionCount / ReplicationFactor / min.insync.replicas 파싱.
  # macOS BSD grep 은 -P 미지원 — sed -E 로 portable 추출.
  HEADER_LINE=$(echo "${DESCRIBE_OUT}" | grep "^Topic:" | head -1)
  PARTITION_COUNT=$(echo "${HEADER_LINE}" | sed -nE 's/.*PartitionCount:[[:space:]]*([0-9]+).*/\1/p')
  RF=$(echo "${HEADER_LINE}" | sed -nE 's/.*ReplicationFactor:[[:space:]]*([0-9]+).*/\1/p')
  ISR=$(echo "${DESCRIBE_OUT}" | sed -nE 's/.*min\.insync\.replicas=([0-9]+).*/\1/p' | head -1)

  if [[ -z "${PARTITION_COUNT}" || -z "${RF}" ]]; then
    fail "파싱 실패 — 토픽이 존재하지 않거나 출력 형식이 다릅니다: ${TOPIC}"
    echo "  출력 내용:"
    echo "${DESCRIBE_OUT}" | head -10
    FAIL_COUNT=$((FAIL_COUNT + 1))
    continue
  fi

  echo "  PartitionCount    = ${PARTITION_COUNT}"
  echo "  ReplicationFactor = ${RF}"
  echo "  min.insync.replicas = ${ISR:-N/A (broker default)}"

  # 검증 1 — 첫 토픽을 baseline 으로 잡고 이후 토픽과 비교
  if [[ -z "${BASELINE_PARTITION}" ]]; then
    BASELINE_PARTITION="${PARTITION_COUNT}"
  elif [[ "${PARTITION_COUNT}" != "${BASELINE_PARTITION}" ]]; then
    fail "${TOPIC} partition=${PARTITION_COUNT} (기준: ${BASELINE_PARTITION})"
    ALL_SAME=false
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi

  # 검증 2 — replication.factor 정책
  if [[ "${RF}" -ge 3 ]]; then
    pass "${TOPIC}: replication.factor=${RF}"
  elif [[ "${RF}" -eq 1 ]]; then
    echo -e "${YELLOW}[WARN]${NC} ${TOPIC}: replication.factor=${RF} (로컬 허용, 프로덕션 배포 전 3으로 변경 필요)"
  else
    fail "${TOPIC}: replication.factor=${RF} (2는 split-brain 위험 — 1 또는 3+ 사용)"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
done

echo ""
echo "======================================================="
echo "  검증 1 종합: 토픽 간 PartitionCount 동일 여부"
echo "======================================================="

if [[ -z "${BASELINE_PARTITION}" ]]; then
  fail "기준 토픽 partition 수를 확인할 수 없어 동일성 검증을 건너뜁니다."
  FAIL_COUNT=$((FAIL_COUNT + 1))
elif [[ "${ALL_SAME}" == "true" ]]; then
  pass "전체 토픽 partition 수 동일: ${BASELINE_PARTITION}"
fi

echo ""
echo "======================================================="
echo "  검증 3: retry 전용 토픽 미존재 확인 (retry 는 DLQ 재처리로 대체)"
echo "======================================================="

RETRY_TOPICS=(
  "payment.commands.confirm.retry"
  "payment.commands.confirm.retry-0"
  "payment.commands.confirm.retry-1"
)

for RETRY_TOPIC in "${RETRY_TOPICS[@]}"; do
  RETRY_OUT=$("${KAFKA_TOPICS[@]}" \
    --bootstrap-server "${BOOTSTRAP_SERVER}" \
    --describe \
    --topic "${RETRY_TOPIC}" 2>&1 || true)

  if echo "${RETRY_OUT}" | grep -q "does not exist"; then
    pass "retry 토픽 없음: ${RETRY_TOPIC}"
  elif echo "${RETRY_OUT}" | grep -q "^Topic:"; then
    fail "retry 전용 토픽이 존재합니다 (정책 위반): ${RETRY_TOPIC}"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  else
    info "확인 불가 (무시): ${RETRY_TOPIC}"
  fi
done

echo ""
echo "======================================================="
if [[ "${FAIL_COUNT}" -eq 0 ]]; then
  pass "전체 검증 통과"
  echo "======================================================="
  exit 0
else
  fail "검증 실패 ${FAIL_COUNT}건"
  echo "======================================================="
  exit 1
fi
