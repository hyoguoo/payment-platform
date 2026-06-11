#!/usr/bin/env bash
# observability-load.sh — 관측성 대시보드 데모용 지속 부하 생성기.
#
# fake 모드 스택에 checkout → confirm 트래픽을 지속 발생시켜 Grafana 대시보드를
# "실수치처럼" 채운다. 실 PG(Toss/NicePay) 호출은 0 — FakePgGatewayStrategy 가 우회한다.
#
# 채워지는 지표(비즈니스 대시보드):
#   - funnel(payment_event_published/terminal_total) · 상태 전이 · 상태 분포(DONE 증가)
#   - 벤더 latency(toss_api_call_*, fake 합성 RTT) · outbox · http_server_requests
#   - 시스템 대시보드(JVM/GC/CPU/HTTP/Hikari/consumer lag) · 서비스 그래프 · exemplar
#
# 이상(실패/보상) 트래픽까지 보려면 pg-service 를 FAKE_FAIL_RATE 와 함께 재기동:
#   FAKE_FAIL_RATE=0.15 docker compose \
#     -f docker/docker-compose.infra.yml -f docker/docker-compose.apps.yml \
#     -f docker/docker-compose.observability.yml -f docker/docker-compose.smoke.yml \
#     up -d --force-recreate pg-service
#   → 약 15% confirm 이 NON_RETRYABLE_FAILURE → FAILED/보상 경로 노출.
#
# 사용법:
#   bash scripts/smoke/observability-load.sh                # 기본 3 rps, 무한 (Ctrl-C 종료)
#   bash scripts/smoke/observability-load.sh --rps 8        # 초당 8건
#   bash scripts/smoke/observability-load.sh --duration 600 # 600초 후 자동 종료
#
# 선행: compose-up.sh --mode fake 로 스택 기동 + redis-stock 시드 완료.

set -uo pipefail

GATEWAY="${GATEWAY:-http://localhost:8090}"
PRODUCT_ID="${PRODUCT_ID:-1}"
USER_ID="${USER_ID:-1}"
REDIS_STOCK_CONTAINER="${REDIS_STOCK_CONTAINER:-payment-redis-stock}"
MYSQL_PRODUCT_CONTAINER="${MYSQL_PRODUCT_CONTAINER:-payment-mysql-product}"
MYSQL_PRODUCT_PASSWORD="${MYSQL_PRODUCT_PASSWORD:-payment123}"
STOCK_TOPUP_VALUE="${STOCK_TOPUP_VALUE:-1000000}"
TOPUP_EVERY="${TOPUP_EVERY:-40}"   # N건마다 재고 보충

RPS=3
DURATION=0   # 0 = 무한

while [[ $# -gt 0 ]]; do
  case "$1" in
    --rps) RPS="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --gateway) GATEWAY="$2"; shift 2 ;;
    --product-id) PRODUCT_ID="$2"; shift 2 ;;
    --user-id) USER_ID="$2"; shift 2 ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
  esac
done

INTERVAL=$(awk "BEGIN { printf \"%.4f\", 1.0 / ${RPS} }")

TOTAL=0; OK=0; ERR=0
START_TS=$(date +%s)

topup_stock() {
  docker exec "${REDIS_STOCK_CONTAINER}" redis-cli SET "stock:${PRODUCT_ID}" "${STOCK_TOPUP_VALUE}" >/dev/null 2>&1 || true
  docker exec -i "${MYSQL_PRODUCT_CONTAINER}" mysql -uroot -p"${MYSQL_PRODUCT_PASSWORD}" -D product \
    -e "UPDATE stock SET quantity=${STOCK_TOPUP_VALUE} WHERE product_id=${PRODUCT_ID};" >/dev/null 2>&1 || true
}

summary() {
  local elapsed=$(( $(date +%s) - START_TS ))
  [ "${elapsed}" -lt 1 ] && elapsed=1
  echo ""
  echo "════════════════════════════════════════════"
  echo " observability-load 종료"
  echo "════════════════════════════════════════════"
  echo "  경과       : ${elapsed}s"
  echo "  총 요청    : ${TOTAL} (confirm 202=${OK} / 오류=${ERR})"
  echo "  실효 rps   : $(awk "BEGIN { printf \"%.2f\", ${TOTAL} / ${elapsed} }")"
  echo "  Grafana    : http://localhost:3000 (대시보드에서 추세 확인)"
  exit 0
}
trap summary INT TERM

echo "[observability-load] gateway=${GATEWAY} rps=${RPS} interval=${INTERVAL}s product=${PRODUCT_ID} user=${USER_ID}"
echo "[observability-load] 재고 ${TOPUP_EVERY}건마다 ${STOCK_TOPUP_VALUE} 로 보충. Ctrl-C 로 종료."
topup_stock

while :; do
  # 1. checkout (201) — 매 요청 고유 Idempotency-Key 로 멱등 dedup 회피(동일 payload 반복 시 같은 주문 재사용 방지).
  CO=$(curl -s -X POST "${GATEWAY}/api/v1/payments/checkout" \
        -H 'Content-Type: application/json' \
        -H "Idempotency-Key: load-$(openssl rand -hex 8)" \
        -d "{\"userId\":${USER_ID},\"gatewayType\":\"TOSS\",\"orderedProductList\":[{\"productId\":${PRODUCT_ID},\"quantity\":1}]}" 2>/dev/null)
  OID=$(echo "${CO}" | grep -o '"orderId":"[^"]*"' | head -1 | sed 's/"orderId":"//;s/"//')
  AMT=$(echo "${CO}" | grep -o '"totalAmount":[0-9.]*' | head -1 | sed 's/"totalAmount"://')

  if [ -n "${OID}" ] && [ -n "${AMT}" ]; then
    # 2. confirm (202) — 정상 요청. 이상(실패)은 pg-service FAKE_FAIL_RATE 가 비동기 경로에서 주입.
    KEY="fake-$(openssl rand -hex 6)"
    HC=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${GATEWAY}/api/v1/payments/confirm" \
          -H 'Content-Type: application/json' \
          -d "{\"userId\":${USER_ID},\"orderId\":\"${OID}\",\"amount\":${AMT},\"paymentKey\":\"${KEY}\",\"gatewayType\":\"TOSS\"}" 2>/dev/null)
    if [ "${HC}" = "202" ]; then OK=$((OK + 1)); else ERR=$((ERR + 1)); fi
  else
    ERR=$((ERR + 1))
  fi

  TOTAL=$((TOTAL + 1))
  if [ $((TOTAL % TOPUP_EVERY)) -eq 0 ]; then
    topup_stock
    echo "[observability-load] 진행 ${TOTAL}건 (202=${OK} 오류=${ERR}) — 재고 보충"
  fi

  if [ "${DURATION}" -gt 0 ] && [ $(( $(date +%s) - START_TS )) -ge "${DURATION}" ]; then
    summary
  fi
  sleep "${INTERVAL}"
done
