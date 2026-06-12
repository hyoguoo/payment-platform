#!/usr/bin/env bash
# observability-load.sh — 관측성 대시보드 데모용 지속 부하 생성기 (커스터마이즈 가능).
#
# fake 모드 스택에 checkout → confirm 트래픽을 지속 발생시켜 Grafana 대시보드를
# "실수치처럼" 채운다. 실 PG(Toss/NicePay) 호출은 0 — FakePgGatewayStrategy 가 우회한다.
#
# ── 4가지 커스터마이즈 축 ───────────────────────────────────────────────
#  1) 부하 곡선   : --profile constant|ramp|wave|spike (--rps-min/--rps-max/--period)
#  2) 요청 믹스   : --qty-min/--qty-max, --users, --products, --gateways
#  3) 라이브 조정 : 컨트롤 파일(--control-file) 매 루프 재읽기 + 시그널(USR1↑ / USR2↓)
#  4) 이상 비율   : --fail-rate X (기동 시 pg-service 를 FAKE_FAIL_RATE=X 로 자동 재기동)
#
# ── 사용 예 ─────────────────────────────────────────────────────────────
#   bash scripts/smoke/observability-load.sh                          # constant 3rps 무한
#   bash scripts/smoke/observability-load.sh --profile wave --rps-min 2 --rps-max 20 --period 90
#   bash scripts/smoke/observability-load.sh --profile spike --rps-min 3 --rps-max 40 --period 60
#   bash scripts/smoke/observability-load.sh --fail-rate 0.15 --profile ramp --rps-max 15
#   bash scripts/smoke/observability-load.sh --gateways TOSS,NICEPAY --qty-min 1 --qty-max 3
#
# ── 라이브 조정 ─────────────────────────────────────────────────────────
#   - 컨트롤 파일(기본 scripts/smoke/.obs-load.control)에 key=value 를 쓰면 매 루프 반영:
#       echo 'RPS_MAX=30'      >  scripts/smoke/.obs-load.control
#       echo 'PROFILE=spike'   >> scripts/smoke/.obs-load.control
#       (지원 키: PROFILE RPS RPS_MIN RPS_MAX PERIOD QTY_MIN QTY_MAX)
#   - 시그널로 즉석 배율 조정:  kill -USR1 <pid>  (×1.5)   kill -USR2 <pid>  (÷1.5)
#   - Ctrl-C → 요약 출력 후 종료.
#
# 선행: compose-up.sh --mode fake 로 스택 기동 + redis-stock 시드 완료.

set -uo pipefail

# ── 기본값 ──────────────────────────────────────────────────────────────
GATEWAY="${GATEWAY:-http://localhost:8090}"
PROFILE="constant"
RPS=3                 # constant 전용 기본 rps (rps-max 미지정 시 이 값 사용)
RPS_MIN=1
RPS_MAX=""            # 미지정 시 RPS 로 채움
PERIOD=60             # ramp/wave/spike 한 주기(초)
DURATION=0            # 0 = 무한
QTY_MIN=1
QTY_MAX=1
USERS="1"
PRODUCTS="1"
GATEWAYS="TOSS"
FAIL_RATE=""          # 지정 시 pg-service 를 이 값으로 재기동
CONTROL_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/.obs-load.control"

# 인프라 컨테이너 / 재고 보충
REDIS_STOCK_CONTAINER="${REDIS_STOCK_CONTAINER:-payment-redis-stock}"
MYSQL_PRODUCT_CONTAINER="${MYSQL_PRODUCT_CONTAINER:-payment-mysql-product}"
MYSQL_PRODUCT_PASSWORD="${MYSQL_PRODUCT_PASSWORD:-payment123}"
STOCK_TOPUP_VALUE="${STOCK_TOPUP_VALUE:-1000000}"
TOPUP_EVERY="${TOPUP_EVERY:-40}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) PROFILE="$2"; shift 2 ;;
    --rps) RPS="$2"; shift 2 ;;
    --rps-min) RPS_MIN="$2"; shift 2 ;;
    --rps-max) RPS_MAX="$2"; shift 2 ;;
    --period) PERIOD="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --qty-min) QTY_MIN="$2"; shift 2 ;;
    --qty-max) QTY_MAX="$2"; shift 2 ;;
    --users) USERS="$2"; shift 2 ;;
    --products) PRODUCTS="$2"; shift 2 ;;
    --gateways) GATEWAYS="$2"; shift 2 ;;
    --gateway) GATEWAY="$2"; shift 2 ;;
    --fail-rate) FAIL_RATE="$2"; shift 2 ;;
    --control-file) CONTROL_FILE="$2"; shift 2 ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 1 ;;
  esac
done

[ -z "${RPS_MAX}" ] && RPS_MAX="${RPS}"
case "${PROFILE}" in constant|ramp|wave|spike) ;; *) echo "잘못된 --profile: ${PROFILE}" >&2; exit 1 ;; esac

# ── (옵션) 이상 비율: pg-service 를 FAKE_FAIL_RATE 로 재기동 ──────────────
if [ -n "${FAIL_RATE}" ]; then
  echo "[observability-load] pg-service 를 FAKE_FAIL_RATE=${FAIL_RATE} 로 재기동(컨테이너 recreate)..."
  ( cd "${ROOT_DIR}" && FAKE_FAIL_RATE="${FAIL_RATE}" docker compose \
      -f docker/docker-compose.infra.yml -f docker/docker-compose.apps.yml \
      -f docker/docker-compose.observability.yml -f docker/docker-compose.smoke.yml \
      up -d --force-recreate pg-service >/dev/null 2>&1 ) || echo "[observability-load] 주의: pg 재기동 실패(스택 미기동?)"
  echo -n "[observability-load] pg healthy 대기"
  for _ in $(seq 1 40); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' docker-pg-service-1 2>/dev/null)" = "healthy" ] && break
    echo -n "."; sleep 2
  done
  echo " ok"
fi

# ── 상태/카운터 ─────────────────────────────────────────────────────────
TOTAL=0; OK=0; ERR=0
START_TS=$(date +%s)
RPS_MULT=1.0   # 시그널 배율

bump_up()   { RPS_MULT=$(awk "BEGIN{printf \"%.3f\", ${RPS_MULT}*1.5}"); echo "[observability-load] USR1 → rps 배율 ${RPS_MULT}"; }
bump_down() { RPS_MULT=$(awk "BEGIN{printf \"%.3f\", ${RPS_MULT}/1.5}"); echo "[observability-load] USR2 → rps 배율 ${RPS_MULT}"; }
trap bump_up USR1
trap bump_down USR2

summary() {
  local elapsed=$(( $(date +%s) - START_TS )); [ "${elapsed}" -lt 1 ] && elapsed=1
  echo ""
  echo "════════════════════════════════════════════"
  echo " observability-load 종료"
  echo "════════════════════════════════════════════"
  echo "  프로파일   : ${PROFILE} (rps-min=${RPS_MIN} rps-max=${RPS_MAX} period=${PERIOD}s)"
  echo "  경과       : ${elapsed}s"
  echo "  총 요청    : ${TOTAL} (confirm 202=${OK} / 오류=${ERR})"
  echo "  실효 rps   : $(awk "BEGIN{printf \"%.2f\", ${TOTAL}/${elapsed}}")"
  echo "  Grafana    : http://localhost:3000"
  exit 0
}
trap summary INT TERM

# 프로파일별 목표 rps 계산 (elapsed 기준). awk 로 float 연산.
compute_rps() {
  local t="$1"
  awk -v p="${PROFILE}" -v t="${t}" -v lo="${RPS_MIN}" -v hi="${RPS_MAX}" \
      -v per="${PERIOD}" -v c="${RPS}" -v mult="${RPS_MULT}" 'BEGIN {
    PI=3.141592653589793;
    if (per<=0) per=1;
    ph=(t - per*int(t/per))/per;          # 0..1 주기 위상
    if (p=="constant")   r=c;
    else if (p=="ramp")  r=lo+(hi-lo)*ph;                       # 톱니 점증
    else if (p=="wave")  r=lo+(hi-lo)*(0.5-0.5*cos(2*PI*ph));   # 사인 0→peak→0
    else if (p=="spike") r=(ph<0.12)?hi:lo;                     # 주기 앞 12% 만 폭증
    else r=c;
    r=r*mult;
    if (r<0.2) r=0.2;                      # 하한(과도 sleep 방지)
    printf "%.3f", r;
  }'
}

pick() { local IFS=','; local arr=($1); echo "${arr[$((RANDOM % ${#arr[@]}))]}"; }

topup_stock() {
  for pid in $(echo "${PRODUCTS}" | tr ',' ' '); do
    docker exec "${REDIS_STOCK_CONTAINER}" redis-cli SET "stock:${pid}" "${STOCK_TOPUP_VALUE}" >/dev/null 2>&1 || true
    docker exec -i "${MYSQL_PRODUCT_CONTAINER}" mysql -uroot -p"${MYSQL_PRODUCT_PASSWORD}" -D product \
      -e "UPDATE stock SET quantity=${STOCK_TOPUP_VALUE} WHERE product_id=${pid};" >/dev/null 2>&1 || true
  done
}

# 컨트롤 파일에서 허용 키만 안전하게 반영(임의 코드 실행 방지 — source 대신 파싱).
apply_control_file() {
  [ -f "${CONTROL_FILE}" ] || return 0
  local key val
  while IFS='=' read -r key val; do
    key="$(echo "${key}" | tr -d '[:space:]')"; val="$(echo "${val}" | tr -d '[:space:]')"
    [ -z "${key}" ] && continue
    case "${key}" in
      PROFILE) PROFILE="${val}" ;;
      RPS) RPS="${val}" ;;
      RPS_MIN) RPS_MIN="${val}" ;;
      RPS_MAX) RPS_MAX="${val}" ;;
      PERIOD) PERIOD="${val}" ;;
      QTY_MIN) QTY_MIN="${val}" ;;
      QTY_MAX) QTY_MAX="${val}" ;;
    esac
  done < "${CONTROL_FILE}"
}

echo "[observability-load] gateway=${GATEWAY} profile=${PROFILE} rps-min=${RPS_MIN} rps-max=${RPS_MAX} period=${PERIOD}s"
echo "[observability-load] mix: qty=${QTY_MIN}-${QTY_MAX} users=[${USERS}] products=[${PRODUCTS}] gateways=[${GATEWAYS}]"
echo "[observability-load] 컨트롤 파일=${CONTROL_FILE} (key=value 로 라이브 조정) · 시그널 USR1↑/USR2↓ · PID=$$"
[ -n "${FAIL_RATE}" ] && echo "[observability-load] 이상 비율 fail-rate=${FAIL_RATE} (pg-service 적용됨)"
echo "[observability-load] Ctrl-C 로 종료."
topup_stock

while :; do
  apply_control_file
  NOW=$(( $(date +%s) - START_TS ))
  CUR_RPS=$(compute_rps "${NOW}")
  INTERVAL=$(awk "BEGIN{printf \"%.4f\", 1.0/${CUR_RPS}}")

  UID_=$(pick "${USERS}"); PID_=$(pick "${PRODUCTS}"); GW_=$(pick "${GATEWAYS}")
  if [ "${QTY_MAX}" -gt "${QTY_MIN}" ]; then
    QTY=$(( QTY_MIN + RANDOM % (QTY_MAX - QTY_MIN + 1) ))
  else
    QTY="${QTY_MIN}"
  fi

  # 1. checkout (201) — 매 요청 고유 Idempotency-Key 로 멱등 dedup 회피.
  CO=$(curl -s -X POST "${GATEWAY}/api/v1/payments/checkout" \
        -H 'Content-Type: application/json' \
        -H "Idempotency-Key: load-$(openssl rand -hex 8)" \
        -d "{\"userId\":${UID_},\"gatewayType\":\"${GW_}\",\"orderedProductList\":[{\"productId\":${PID_},\"quantity\":${QTY}}]}" 2>/dev/null)
  OID=$(echo "${CO}" | grep -o '"orderId":"[^"]*"' | head -1 | sed 's/"orderId":"//;s/"//')
  AMT=$(echo "${CO}" | grep -o '"totalAmount":[0-9.]*' | head -1 | sed 's/"totalAmount"://')

  if [ -n "${OID}" ] && [ -n "${AMT}" ]; then
    KEY="fake-$(openssl rand -hex 6)"
    HC=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${GATEWAY}/api/v1/payments/confirm" \
          -H 'Content-Type: application/json' \
          -d "{\"userId\":${UID_},\"orderId\":\"${OID}\",\"amount\":${AMT},\"paymentKey\":\"${KEY}\",\"gatewayType\":\"${GW_}\"}" 2>/dev/null)
    if [ "${HC}" = "202" ]; then OK=$((OK + 1)); else ERR=$((ERR + 1)); fi
  else
    ERR=$((ERR + 1))
  fi

  TOTAL=$((TOTAL + 1))
  if [ $((TOTAL % TOPUP_EVERY)) -eq 0 ]; then
    topup_stock
    echo "[observability-load] ${TOTAL}건 (202=${OK} 오류=${ERR}) | 목표 ${CUR_RPS} rps (${PROFILE}, 배율 ${RPS_MULT}) | 재고 보충"
  fi

  if [ "${DURATION}" -gt 0 ] && [ "${NOW}" -ge "${DURATION}" ]; then summary; fi
  sleep "${INTERVAL}"
done
