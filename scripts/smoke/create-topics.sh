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
# 멱등: 이미 토픽이 존재하면 에러 없이 스킵(단, 이미 존재하는 토픽은 retention 등 config 를
# 재적용하지 않는다 — 아래 "기존 토픽 config 갱신" 절차 참고).
#
# events.confirmed.dlq retention:
#   재주입 나이 게이트(DlqReprocessUseCase — DONE 종결 시각 + P8D = 8일)보다 반드시 커야
#   한다. retention 이 이보다 짧으면 게이트는 아직 열려 있는데(재주입 허용 구간) 브로커가
#   먼저 메시지를 지워 재주입 불가 사각이 생긴다. 8일 초과 + 과도하지 않은 운영 버퍼로
#   10일(EVENTS_CONFIRMED_DLQ_RETENTION_MS, KafkaTopicConfig 의 선언 SoT 와 동일 값)을 채택.
#   이 스크립트가 신규 생성 시 --config 로 적용하는 실제 값의 SoT 다
#   (KafkaTopicConfig 는 테스트/임베디드 환경용 선언일 뿐, auto.create.topics.enable=false
#   운영 환경의 실제 토픽 생성은 이 스크립트가 담당한다).
#
# 기존 토픽 config 갱신(이미 생성된 토픽에 retention 을 뒤늦게 적용/변경할 때):
#   docker exec payment-kafka kafka-configs \
#     --bootstrap-server localhost:9092 \
#     --entity-type topics --entity-name payment.events.confirmed.dlq \
#     --alter --add-config retention.ms=864000000
#   # 확인:
#   docker exec payment-kafka kafka-configs \
#     --bootstrap-server localhost:9092 \
#     --entity-type topics --entity-name payment.events.confirmed.dlq \
#     --describe

set -euo pipefail

CONTAINER="payment-kafka"
BOOTSTRAP="localhost:9092"
PARTITIONS=3
REPLICATION=1  # 로컬 단일 브로커. 프로덕션=3
DLQ_RETENTION_MS=864000000  # 10일 — 위 "events.confirmed.dlq retention" 절 참고

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
    create_args=(
      --bootstrap-server "${BOOTSTRAP}"
      --create
      --topic "${topic}"
      --partitions "${PARTITIONS}"
      --replication-factor "${REPLICATION}"
    )
    if [[ "${topic}" == "payment.events.confirmed.dlq" ]]; then
      create_args+=(--config "retention.ms=${DLQ_RETENTION_MS}")
    fi
    docker exec "${CONTAINER}" kafka-topics "${create_args[@]}" 2>/dev/null
    if [[ "${topic}" == "payment.events.confirmed.dlq" ]]; then
      echo -e "${GREEN}[CREATE]${NC} 생성 완료: ${topic} (partitions=${PARTITIONS}, rf=${REPLICATION}, retention.ms=${DLQ_RETENTION_MS})"
    else
      echo -e "${GREEN}[CREATE]${NC} 생성 완료: ${topic} (partitions=${PARTITIONS}, rf=${REPLICATION})"
    fi
  fi
done

echo ""
echo "완료. phase-0-gate.sh 실행으로 검증하세요."
