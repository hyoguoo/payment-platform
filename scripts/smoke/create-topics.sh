#!/usr/bin/env bash
# create-topics.sh — Kafka 토픽 초기 생성 스크립트
# payment 도메인 운영 토픽 5개(운영 3 + DLQ 2)를 동일 파티션 수로 생성한다.
#
# 전제조건:
#   - docker-compose.infra.yml up 완료 후 실행
#   - auto.create.topics.enable=false 설정 전제 (Kafka 브로커)
#
# 실행:
#   bash scripts/phase-gate/create-topics.sh
#
# 멱등: 이미 토픽이 존재하면 에러 없이 스킵

set -euo pipefail

CONTAINER="payment-kafka"
BOOTSTRAP="localhost:9092"
PARTITIONS=3
REPLICATION=1  # 로컬 단일 브로커. 프로덕션=3

TOPICS=(
  "payment.commands.confirm"
  "payment.commands.confirm.dlq"
  "payment.events.confirmed"
  "payment.events.confirmed.dlq"
  "payment.events.stock-committed"
)

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo "Kafka 토픽 생성 시작 (bootstrap=${BOOTSTRAP})"
echo ""

for topic in "${TOPICS[@]}"; do
  # 이미 존재하면 스킵
  exists=$(docker exec "${CONTAINER}" kafka-topics \
    --bootstrap-server "${BOOTSTRAP}" \
    --list 2>/dev/null | grep -cx "${topic}" || true)

  if [[ "${exists}" -gt 0 ]]; then
    echo -e "${YELLOW}[SKIP]${NC} 이미 존재: ${topic}"
  else
    docker exec "${CONTAINER}" kafka-topics \
      --bootstrap-server "${BOOTSTRAP}" \
      --create \
      --topic "${topic}" \
      --partitions "${PARTITIONS}" \
      --replication-factor "${REPLICATION}" 2>/dev/null
    echo -e "${GREEN}[CREATE]${NC} 생성 완료: ${topic} (partitions=${PARTITIONS}, rf=${REPLICATION})"
  fi
done

echo ""
echo "완료. phase-0-gate.sh 실행으로 검증하세요."
