#!/usr/bin/env bash
# trace-continuity-check.sh — HTTP → Kafka → HTTP 다중 홉에서 traceId 가
# 5개 서비스(gateway/payment-service/pg-service/product-service/user-service) 로그에
# 연속 등장하는지 자동 검증하는 smoke 스크립트.
#
# 사용법:
#   ./scripts/smoke/trace-continuity-check.sh
#
# 선행 조건:
#   1. docker compose 로 전체 스택(infra + apps + smoke override)이 기동 중이어야 한다.
#      기동 방법: bash scripts/compose-up.sh 후
#        docker compose \
#          -f docker/docker-compose.infra.yml \
#          -f docker/docker-compose.apps.yml \
#          -f docker/docker-compose.smoke.yml \
#          up -d --force-recreate --no-deps pg-service
#   2. 스택이 기동 중이 아니면 이 스크립트가 --auto-compose-up 없이 종료한다.
#   3. pg-service 는 PG_GATEWAY_TYPE=fake (smoke override) 로 기동 중이어야 한다.
#
# 옵션:
#   --auto-compose-up   스택이 기동 중이 아닐 때 자동으로 compose-up 시도
#   --verbose           각 단계 상세 로그 출력
#
# 재현 절차:
#   # 1. 스택 기동 (이미 기동 중이면 생략)
#   bash scripts/compose-up.sh
#   docker compose \
#     -f docker/docker-compose.infra.yml \
#     -f docker/docker-compose.apps.yml \
#     -f docker/docker-compose.smoke.yml \
#     up -d --force-recreate --no-deps pg-service
#   sleep 15
#
#   # 2. 스크립트 실행
#   bash scripts/smoke/trace-continuity-check.sh
#
# 실패 시 조치:
#   - gateway 미등장: gateway logback-spring.xml traceId MDC 패턴 또는
#     TraceContextPropagationFilter 확인
#   - payment-service 미등장: MdcContextPropagationConfig (MDC 전파) 확인
#   - pg-service 미등장: PgSlf4jMdcThreadLocalAccessor 등록 확인
#   - product-service / user-service 미등장: payment-service Feign 클라이언트 또는
#     product/user 자체 클라이언트의 observationRegistry 상속 확인 (traceparent 자동 전파)
#   - Kafka consumer 경로 미등장: spring.kafka.listener.observation-enabled=true 확인
#
# 종료 코드:
#   0 — traceId 가 5개 서비스 로그 모두에서 발견됨 (다중 홉 연속성 PASS)
#   1 — 하나 이상 서비스에서 traceId 미발견 또는 선행 조건 실패

set -euo pipefail

# ─────────────────────────────────────────────
# 상수 / 환경변수
# ─────────────────────────────────────────────
GATEWAY_BASE="${GATEWAY_BASE:-http://localhost:8090}"
COMPOSE_PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_APPS="${COMPOSE_PROJECT_ROOT}/docker/docker-compose.apps.yml"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-docker}"

LOG_WAIT_SECONDS="${LOG_WAIT_SECONDS:-20}"   # confirm 처리 + 비동기 relay 완주 대기 시간
POLL_TIMEOUT_SECONDS="${POLL_TIMEOUT_SECONDS:-30}"  # status=DONE 폴링 최대 대기 시간

AUTO_COMPOSE_UP=false
VERBOSE=false

for arg in "$@"; do
  case "${arg}" in
    --auto-compose-up) AUTO_COMPOSE_UP=true ;;
    --verbose)         VERBOSE=true ;;
    -h|--help)
      sed -n '2,55p' "$0"
      exit 0
      ;;
    *)
      echo "[ERROR] 알 수 없는 옵션: ${arg}" >&2
      exit 1
      ;;
  esac
done

dbg() { [[ "${VERBOSE}" == "true" ]] && echo "  [DEBUG] $*" || true; }

# ─────────────────────────────────────────────
# 0. 선행 조건 — 필수 명령어
# ─────────────────────────────────────────────
echo "[INFO] 선행 조건 확인..."
for cmd in docker curl openssl; do
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "[ERROR] 필수 명령어 없음: ${cmd}" >&2
    exit 1
  fi
done

# ─────────────────────────────────────────────
# 1. 스택 기동 확인
# ─────────────────────────────────────────────
echo "[INFO] 스택 기동 상태 확인..."
GATEWAY_OK=$(curl -sf "${GATEWAY_BASE}/actuator/health" 2>/dev/null | grep -c '"UP"' || echo "0")

if [[ "${GATEWAY_OK}" == "0" ]]; then
  if [[ "${AUTO_COMPOSE_UP}" == "true" ]]; then
    echo "[INFO] 스택 미기동 — compose-up 자동 실행..."
    bash "${COMPOSE_PROJECT_ROOT}/scripts/compose-up.sh"
    docker compose \
      -f "${COMPOSE_PROJECT_ROOT}/docker/docker-compose.infra.yml" \
      -f "${COMPOSE_PROJECT_ROOT}/docker/docker-compose.apps.yml" \
      -f "${COMPOSE_PROJECT_ROOT}/docker/docker-compose.smoke.yml" \
      up -d --force-recreate --no-deps pg-service
    echo "[INFO] pg-service smoke override 재기동 완료 — 15s 안정화 대기..."
    sleep 15
  else
    echo "[ERROR] 스택 미기동 (${GATEWAY_BASE} 응답 없음)." >&2
    echo "        --auto-compose-up 옵션을 사용하거나 수동으로 compose-up 후 재실행하세요." >&2
    exit 1
  fi
fi
echo "[INFO] 스택 기동 확인 완료."

# ─────────────────────────────────────────────
# 2. 고정 traceId 생성 (W3C traceparent 포맷)
#    00-<32 hex trace-id>-<16 hex span-id>-01
# ─────────────────────────────────────────────
TRACE_ID="$(openssl rand -hex 16)"    # 32 hex 문자 (16 bytes)
SPAN_ID="$(openssl rand -hex 8)"      # 16 hex 문자 (8 bytes)
TRACEPARENT="00-${TRACE_ID}-${SPAN_ID}-01"

echo "[INFO] 테스트 traceparent 생성: ${TRACEPARENT}"
echo "[INFO]   trace-id: ${TRACE_ID}"

# ─────────────────────────────────────────────
# 3. user-service 호출 (200 기대) — user-service 로그에 traceId 주입
# ─────────────────────────────────────────────
echo "[INFO] GET /api/v1/users/1 요청..."
USER_HTTP_CODE=$(curl -sS -o /tmp/trace-smoke-user.json -w "%{http_code}" \
  -X GET "${GATEWAY_BASE}/api/v1/users/1" \
  -H "traceparent: ${TRACEPARENT}" \
  2>/dev/null || echo "000")

if [[ "${USER_HTTP_CODE}" != "200" ]]; then
  echo "[ERROR] GET /users/1 실패: HTTP ${USER_HTTP_CODE}" >&2
  head -5 /tmp/trace-smoke-user.json >&2 2>/dev/null || true
  exit 1
fi
echo "[INFO] user 200 OK"

# ─────────────────────────────────────────────
# 4. checkout 요청 (201 기대)
# ─────────────────────────────────────────────
echo "[INFO] POST /api/v1/payments/checkout 요청..."
IDEM_KEY="trace-smoke-$(date +%s)-${SPAN_ID}"
CHECKOUT_PAYLOAD='{"userId":1,"gatewayType":"TOSS","orderedProductList":[{"productId":1,"quantity":1}]}'

CHECKOUT_HTTP_CODE=$(curl -sS \
  -o /tmp/trace-smoke-checkout.json \
  -w "%{http_code}" \
  -X POST "${GATEWAY_BASE}/api/v1/payments/checkout" \
  -H "Content-Type: application/json" \
  -H "traceparent: ${TRACEPARENT}" \
  -H "Idempotency-Key: ${IDEM_KEY}" \
  -d "${CHECKOUT_PAYLOAD}" \
  2>/dev/null || echo "000")

if [[ "${CHECKOUT_HTTP_CODE}" != "201" ]]; then
  echo "[ERROR] checkout 실패: HTTP ${CHECKOUT_HTTP_CODE}" >&2
  echo "        응답: $(cat /tmp/trace-smoke-checkout.json 2>/dev/null)" >&2
  exit 1
fi

ORDER_ID=$(cat /tmp/trace-smoke-checkout.json | grep -o '"orderId":"[^"]*"' | head -1 | sed 's/"orderId":"//;s/"//' || echo "")
TOTAL_AMOUNT=$(cat /tmp/trace-smoke-checkout.json | grep -o '"totalAmount":[0-9.]*' | head -1 | sed 's/"totalAmount"://' || echo "0")

dbg "checkout 응답 body: $(cat /tmp/trace-smoke-checkout.json)"

if [[ -z "${ORDER_ID}" || "${ORDER_ID}" == "null" ]]; then
  echo "[ERROR] checkout 응답에서 orderId 추출 실패" >&2
  echo "        응답: $(cat /tmp/trace-smoke-checkout.json 2>/dev/null)" >&2
  exit 1
fi
echo "[INFO] checkout 201 — orderId=${ORDER_ID} totalAmount=${TOTAL_AMOUNT}"

# ─────────────────────────────────────────────
# 5. confirm 요청 (202 기대)
# ─────────────────────────────────────────────
echo "[INFO] POST /api/v1/payments/confirm 요청..."
FAKE_PAYMENT_KEY="fake-trace-${ORDER_ID}"
CONFIRM_PAYLOAD="{\"userId\":1,\"orderId\":\"${ORDER_ID}\",\"amount\":${TOTAL_AMOUNT},\"paymentKey\":\"${FAKE_PAYMENT_KEY}\",\"gatewayType\":\"TOSS\"}"

CONFIRM_HTTP_CODE=$(curl -sS \
  -o /tmp/trace-smoke-confirm.json \
  -w "%{http_code}" \
  -X POST "${GATEWAY_BASE}/api/v1/payments/confirm" \
  -H "Content-Type: application/json" \
  -H "traceparent: ${TRACEPARENT}" \
  -d "${CONFIRM_PAYLOAD}" \
  2>/dev/null || echo "000")

if [[ "${CONFIRM_HTTP_CODE}" != "202" ]]; then
  echo "[ERROR] confirm 실패: HTTP ${CONFIRM_HTTP_CODE}" >&2
  echo "        응답: $(cat /tmp/trace-smoke-confirm.json 2>/dev/null)" >&2
  exit 1
fi
echo "[INFO] confirm 202 Accepted — 비동기 승인 진입"

# ─────────────────────────────────────────────
# 6. status 폴링 → DONE 대기
# ─────────────────────────────────────────────
echo "[INFO] status 폴링 (최대 ${POLL_TIMEOUT_SECONDS}s)..."
DEADLINE=$(( $(date +%s) + POLL_TIMEOUT_SECONDS ))
FINAL_STATUS=""
while (( $(date +%s) < DEADLINE )); do
  STATUS_RESP=$(curl -sS "${GATEWAY_BASE}/api/v1/payments/${ORDER_ID}/status" 2>/dev/null || echo "")
  CUR=$(echo "${STATUS_RESP}" | grep -o '"status":"[^"]*"' | head -1 | sed 's/"status":"//;s/"//' || echo "")
  dbg "  polling status=${CUR}"
  if [[ "${CUR}" == "DONE" || "${CUR}" == "FAILED" ]]; then
    FINAL_STATUS="${CUR}"
    break
  fi
  sleep 2
done

if [[ "${FINAL_STATUS}" != "DONE" ]]; then
  echo "[ERROR] status 폴링 실패: ${POLL_TIMEOUT_SECONDS}s 내 DONE 미도달 (마지막=${FINAL_STATUS:-unreachable})" >&2
  echo "        FakePgGatewayStrategy 가 로드됐는지, smoke override 적용됐는지 확인하세요." >&2
  exit 1
fi
echo "[INFO] status=DONE 확인 — 5-service chain 완주"

# ─────────────────────────────────────────────
# 7. 비동기 relay(Kafka consumer + outbox relay) 로그 안정화 대기
# ─────────────────────────────────────────────
echo "[INFO] 비동기 relay 완주 대기 ${LOG_WAIT_SECONDS}s..."
sleep "${LOG_WAIT_SECONDS}"

# ─────────────────────────────────────────────
# 8. 로그 수집 + traceId 등장 여부 검증
#    MDC 패턴: [traceId:<traceId>] (logback-spring.xml LOG_PATTERN)
# ─────────────────────────────────────────────
echo "[INFO] docker compose 로그 수집 (최근 5분)..."

# 단일 컨테이너의 로그에서 traceId 검색 (verbose 시 sample 라인 출력).
# return: 0 = 발견, 1 = 미발견.
find_trace_in_logs() {
  local container="$1"
  local svc_log
  svc_log=$(docker logs --since=5m "${container}" 2>&1 || echo "")
  if echo "${svc_log}" | grep -q "traceId:${TRACE_ID}"; then
    if [[ "${VERBOSE}" == "true" ]]; then
      echo "${svc_log}" | grep "traceId:${TRACE_ID}" | head -3 | sed 's/^/         /'
    fi
    return 0
  fi
  return 1
}

# product-service 는 오토스케일링 대상이라 동적 검사. 그 외는 단일 컨테이너 이름.
SERVICES=("gateway" "payment-service" "pg-service" "user-service")

MISSING_SERVICES=()

for svc in "${SERVICES[@]}"; do
  if find_trace_in_logs "${svc}"; then
    echo "[PASS] ${svc}: traceId 발견 (traceId:${TRACE_ID})"
  else
    echo "[FAIL] ${svc}: traceId 미발견 (traceId:${TRACE_ID})"
    MISSING_SERVICES+=("${svc}")
  fi
done

# product-service — scale=N 대응 (docker-product-service-{1..N}).
# Feign LB 가 한 인스턴스로만 호출을 보내므로, traceId 가 어느 한 인스턴스에 있어도 PASS.
PRODUCT_INSTANCES=$(docker ps --filter "name=${COMPOSE_PROJECT_NAME}-product-service-" --format "{{.Names}}" 2>/dev/null)
if [ -z "${PRODUCT_INSTANCES}" ]; then
  echo "[FAIL] product-service: 인스턴스 0건 (compose up 안 됐나?)"
  MISSING_SERVICES+=("product-service")
else
  PRODUCT_FOUND=false
  while IFS= read -r instance; do
    if find_trace_in_logs "${instance}"; then
      PRODUCT_FOUND=true
      echo "[PASS] product-service (${instance}): traceId 발견 (traceId:${TRACE_ID})"
    fi
  done <<< "${PRODUCT_INSTANCES}"
  if [[ "${PRODUCT_FOUND}" == "false" ]]; then
    echo "[FAIL] product-service: 모든 인스턴스에서 traceId 미발견 (인스턴스: $(echo "${PRODUCT_INSTANCES}" | tr '\n' ' '))"
    MISSING_SERVICES+=("product-service")
  fi
fi

# ─────────────────────────────────────────────
# 9. payment-service + pg-service 추가 검증
#    — Kafka consumer / outbox relay 경로 로그에도 등장해야 함
# ─────────────────────────────────────────────
echo ""
echo "[INFO] payment-service Kafka listener 경로 추가 검증..."
PAYMENT_LOG=$(docker logs --since=5m payment-service 2>&1 || echo "")

# PaymentConfirmResultUseCase 또는 ConfirmedEventConsumer 로그에 traceId 등장 여부
PAYMENT_KAFKA_HIT=$(echo "${PAYMENT_LOG}" | grep "traceId:${TRACE_ID}" | grep -ci "confirm\|consumer\|kafka\|relay\|result" || true)
if [[ "${PAYMENT_KAFKA_HIT}" -gt 0 ]]; then
  echo "[PASS] payment-service Kafka listener 경로에서 traceId 발견 (${PAYMENT_KAFKA_HIT}건)"
else
  # Kafka listener 경로 로그가 없어도 payment-service 전체에서 이미 확인됐으면 경고만
  if echo "${PAYMENT_LOG}" | grep -q "traceId:${TRACE_ID}"; then
    echo "[INFO] payment-service Kafka listener 경로 특정 불가 — 전체 로그에서는 발견됨 (로그 레벨 확인 권장)"
  else
    echo "[WARN] payment-service Kafka listener 경로에서 traceId 미발견 — MdcContextPropagationConfig 점검"
  fi
fi

echo "[INFO] pg-service Kafka consumer 경로 추가 검증..."
PG_LOG=$(docker logs --since=5m pg-service 2>&1 || echo "")

PG_KAFKA_HIT=$(echo "${PG_LOG}" | grep "traceId:${TRACE_ID}" | grep -ci "consumer\|kafka\|relay\|outbox\|confirm\|payment" || true)
if [[ "${PG_KAFKA_HIT}" -gt 0 ]]; then
  echo "[PASS] pg-service Kafka consumer/outbox relay 경로에서 traceId 발견 (${PG_KAFKA_HIT}건)"
else
  if echo "${PG_LOG}" | grep -q "traceId:${TRACE_ID}"; then
    echo "[INFO] pg-service Kafka consumer 경로 특정 불가 — 전체 로그에서는 발견됨 (로그 레벨 확인 권장)"
  else
    echo "[WARN] pg-service Kafka consumer 경로에서 traceId 미발견 — PgSlf4jMdcThreadLocalAccessor 점검"
  fi
fi

# ─────────────────────────────────────────────
# 10. 최종 판정
# ─────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════"
echo " trace-continuity-check 결과"
echo "════════════════════════════════════════════"
echo " trace-id  : ${TRACE_ID}"
echo " orderId   : ${ORDER_ID}"
echo " 검증 서비스: ${SERVICES[*]} product-service($(echo "${PRODUCT_INSTANCES:-0건}" | tr '\n' ',' | sed 's/,$//'))"
echo ""

if [[ ${#MISSING_SERVICES[@]} -eq 0 ]]; then
  echo "[PASS] 다중 홉 traceId 연속성 검증 완료 — 5개 서비스 모두 발견"
  echo ""
  echo "  HTTP 수신 (gateway) → payment-service → Kafka confirm →"
  echo "  pg-service (FakePgGatewayStrategy) → Kafka confirmed →"
  echo "  payment-service (ConfirmedEventConsumer) → product-service / user-service"
  echo ""
  echo "  T-Gate: scripts/smoke/trace-continuity-check.sh PASS"
  exit 0
else
  echo "[FAIL] traceId 미발견 서비스: ${MISSING_SERVICES[*]}"
  echo ""
  echo "  원인 트리아지 가이드:"
  for svc in "${MISSING_SERVICES[@]}"; do
    case "${svc}" in
      gateway)
        echo "    · gateway: TraceContextPropagationFilter MDC 주입 여부 / logback-spring.xml traceId 패턴 확인"
        ;;
      payment-service)
        echo "    · payment-service: MdcContextPropagationConfig @PostConstruct registerMdcAccessor 확인"
        echo "                       WebClient.Builder auto-config 상속 확인"
        ;;
      pg-service)
        echo "    · pg-service: PgServiceConfig @PostConstruct registerMdcAccessor 확인"
        echo "                  PgOutboxImmediateWorker ContextExecutorService.wrap 확인"
        echo "                  spring.kafka.listener.observation-enabled=true 확인"
        ;;
      product-service)
        echo "    · product-service: RestClient.Builder / WebClient.Builder observationRegistry 상속 확인"
        echo "                       stock-commit Kafka consumer MDC 전파 확인"
        ;;
      user-service)
        echo "    · user-service: HTTP 요청 수신 시 MDC 주입 여부 확인"
        ;;
    esac
  done
  echo ""
  echo "  docs/smoke/trace-continuity-check.md 가이드를 참고하세요."
  exit 1
fi
