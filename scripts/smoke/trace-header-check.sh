#!/usr/bin/env bash
# trace-header-check.sh — Kafka record header 에 W3C traceparent 가 주입되었는지 확인하는 smoke 스크립트.
#
# 사용법:
#   ./scripts/smoke/trace-header-check.sh
#
# 선행 조건:
#   1. docker compose up 으로 전체 스택(Kafka·payment-service·pg-service 등)이 정상 기동 중이어야 한다.
#   2. 스크립트 실행 전에 결제 checkout 요청이 최소 1건 처리된 상태여야 한다.
#      예: curl -s -X POST http://localhost:8081/api/v1/payments/checkout -H 'Content-Type: application/json' \
#              -d '{"userId":1,"orderId":"smoke-test-001","amount":1000,"productId":1}'
#   3. kafka-console-consumer 가 PATH 에 있거나 Kafka 컨테이너 내 실행 가능해야 한다.
#      컨테이너 외부에서 실행 시: KAFKA_CONTAINER 환경변수로 컨테이너명 지정 가능 (기본: payment-kafka).
#
# 출력 예시 (성공):
#   [OK] traceparent header 확인됨:
#        traceparent:00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
#
# 출력 예시 (실패):
#   [ERROR] payment.commands.confirm 토픽 메시지에서 traceparent header 를 찾을 수 없습니다.
#           Kafka observation-enabled 설정 또는 Micrometer Tracing 의존성을 확인하세요.
#
# 종료 코드:
#   0 — traceparent 헤더 확인 성공
#   1 — traceparent 헤더 미발견 또는 Kafka 연결 실패

set -euo pipefail

TOPIC="${KAFKA_TOPIC:-payment.commands.confirm}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-payment-kafka}"
BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-20}"
MAX_MESSAGES="${MAX_MESSAGES:-100}"

echo "[INFO] Kafka topic '$TOPIC' 에서 traceparent header 확인 시작..."
echo "[INFO] bootstrap-servers: $BOOTSTRAP_SERVERS (timeout: ${TIMEOUT_SECONDS}s)"

# kafka-console-consumer 실행 — print.headers=true 로 record header 포함 출력
# timeout 후 자동 종료 (메시지가 없을 경우 무한 대기 방지)
if command -v kafka-console-consumer &>/dev/null; then
    CONSUMER_CMD="kafka-console-consumer"
elif docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${KAFKA_CONTAINER}$"; then
    # Kafka 이미지는 배포판에 따라 .sh 접미어가 있을 수도 있어 uppercase 도 시도.
    if docker exec "${KAFKA_CONTAINER}" bash -c 'command -v kafka-console-consumer' >/dev/null 2>&1; then
        CONSUMER_CMD="docker exec ${KAFKA_CONTAINER} kafka-console-consumer"
    else
        CONSUMER_CMD="docker exec ${KAFKA_CONTAINER} kafka-console-consumer.sh"
    fi
else
    echo "[ERROR] kafka-console-consumer 를 찾을 수 없고 컨테이너 '${KAFKA_CONTAINER}' 도 실행 중이 아닙니다."
    echo "        KAFKA_CONTAINER 환경변수로 Kafka 컨테이너명을 지정하거나 kafka-console-consumer 를 PATH 에 추가하세요."
    exit 1
fi

# T3.5-13 이후 발행된 메시지만 traceparent 헤더가 주입되므로 최근 메시지를 찾기 위해 max-messages 를 늘림.
# macOS 는 기본적으로 `timeout` 바이너리가 없으므로(coreutils 미설치 시) kafka 자체
# --timeout-ms 에만 의존. gtimeout 가 있으면 우선 사용.
if command -v timeout &>/dev/null; then
    TIMEOUT_WRAP="timeout ${TIMEOUT_SECONDS}"
elif command -v gtimeout &>/dev/null; then
    TIMEOUT_WRAP="gtimeout ${TIMEOUT_SECONDS}"
else
    TIMEOUT_WRAP=""
fi

OUTPUT=$(
    ${TIMEOUT_WRAP} ${CONSUMER_CMD} \
        --bootstrap-server "${BOOTSTRAP_SERVERS}" \
        --topic "${TOPIC}" \
        --from-beginning \
        --max-messages "${MAX_MESSAGES}" \
        --property print.headers=true \
        --property print.timestamp=true \
        --timeout-ms $(( TIMEOUT_SECONDS * 1000 )) \
        2>/dev/null || true
)

if echo "$OUTPUT" | grep -q "traceparent:"; then
    HEADER_LINE=$(echo "$OUTPUT" | grep "traceparent:" | head -1)
    echo "[OK] traceparent header 확인됨:"
    echo "     $HEADER_LINE"
    exit 0
else
    echo "[ERROR] payment.commands.confirm 토픽 메시지에서 traceparent header 를 찾을 수 없습니다."
    echo "        확인 사항:"
    echo "          1. spring.kafka.template.observation-enabled=true 설정 여부"
    echo "          2. spring.kafka.listener.observation-enabled=true 설정 여부"
    echo "          3. KafkaTemplate 수동 빈에 setObservationEnabled(true) 호출 여부"
    echo "          4. micrometer-tracing-bridge-otel 의존성 존재 여부"
    echo "          5. management.tracing.sampling.probability > 0.0 설정 여부"
    echo ""
    echo "        수집된 출력 (디버그):"
    echo "$OUTPUT" | head -20
    exit 1
fi
