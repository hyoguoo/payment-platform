#!/bin/bash
set -e
source "$(dirname "$0")/../common.sh"

SCRIPT_DIR="$(dirname "$(realpath "$0")")"
BASE_URL="${BASE_URL:-http://host.docker.internal:8080}"
COMPOSE_FILE="$(dirname "$(dirname "${SCRIPT_DIR}")")/docker/compose/docker-compose.yml"
RESET_SQL="${SCRIPT_DIR}/../benchmark-reset.sql"

DB_CONTAINER="${DB_CONTAINER:-payment-mysql}"
DB_USER="${MYSQL_USER:-payment}"
DB_PASSWORD="${MYSQL_PASSWORD:-payment123}"
DB_NAME="payment-platform"

print_section "=== Benchmark: Sync vs Async VT ==="
print_warning "전제 조건:"
echo "  1. Docker가 실행 중이어야 합니다"
echo "  2. docker/compose/docker-compose.yml 로 전체 스택이 기동 중이어야 합니다"
echo "  3. MySQL이 실행 중이어야 합니다 (docker/compose)"
echo ""
echo "  ★ Tomcat: 항상 PT (spring.threads.virtual.enabled=false)"
echo "  ★ Async Worker: 항상 VT (outbox.channel.virtual-threads=true)"
echo ""
echo "  부하 단계 (고/저지연 동일):"
echo "    warm-up  0→20 req/s (20s)"
echo "    ramp     20→100 req/s (30s)   ← sync 포화점(~73) 크게 초과"
echo "    steady   100 req/s (90s)      ← 차이 극명 구간"
echo ""

reset_data() {
  print_info "데이터 초기화 중 (benchmark-reset.sql)..."
  docker exec -i "${DB_CONTAINER}" mysql -u"${DB_USER}" -p"${DB_PASSWORD}" "${DB_NAME}" < "${RESET_SQL}"
  print_info "데이터 초기화 완료."
}

switch_strategy() {
  local strategy=$1
  local toss_min_delay=$2
  local toss_max_delay=$3

  print_info "전략: ${strategy} | Toss 지연: ${toss_min_delay}~${toss_max_delay}ms"

  BENCHMARK_PROFILE=",benchmark" \
  ASYNC_STRATEGY="${strategy}" \
  TOSS_MIN_DELAY_MILLIS="${toss_min_delay}" \
  TOSS_MAX_DELAY_MILLIS="${toss_max_delay}" \
    docker compose -f "${COMPOSE_FILE}" up -d --no-deps app

  print_info "앱 헬스체크 대기 중..."
  local max_wait=120
  local elapsed=0
  until curl -sf "http://localhost:8080/actuator/health" > /dev/null 2>&1; do
    sleep 3
    elapsed=$((elapsed + 3))
    if [ "${elapsed}" -ge "${max_wait}" ]; then
      print_warning "헬스체크 타임아웃 (${max_wait}s). 계속 진행합니다."
      break
    fi
  done
  print_info "앱 준비 완료."
}

run_k6() {
  local script=$1
  local testid=$2
  mkdir -p "${SCRIPT_DIR}/results"
  docker run --rm \
    -v "${SCRIPT_DIR}:/scripts" \
    -e BASE_URL="${BASE_URL}" \
    -e K6_PROMETHEUS_RW_SERVER_URL="http://host.docker.internal:9090/api/v1/write" \
    -e CASE_NAME="${testid}" \
    grafana/k6 run \
      --out experimental-prometheus-rw \
      --tag testid="${testid}" \
      "/scripts/${script}"
}

run_sync() {
  local testid=$1
  local toss_min=$2
  local toss_max=$3
  echo ""
  print_section "--- ${testid} ---"
  switch_strategy "sync" "${toss_min}" "${toss_max}"
  reset_data
  run_k6 "sync.js" "${testid}"
  print_info "${testid} 완료."
}

run_outbox() {
  local testid=$1
  local toss_min=$2
  local toss_max=$3
  echo ""
  print_section "--- ${testid} ---"
  switch_strategy "outbox" "${toss_min}" "${toss_max}"
  reset_data
  run_k6 "outbox.js" "${testid}"
  print_info "${testid} 완료."
}

# =============================================================
# 비교 매트릭스
#
# 핵심 비교 (고지연 800~1500ms):
#   sync-high  →  outbox-high
#   Toss 지연이 길수록 sync는 Tomcat thread를 길게 점유 → TPS 붕괴
#   async는 HTTP thread가 즉시 반환 → TPS 유지, E2E latency만 Toss 처리 시간
#
# 참조 비교 (저지연 100~300ms):
#   sync-low  →  outbox-low
#   저지연에서는 sync도 thread 포화가 늦게 옴 → 차이 작음
#   HTTP p95는 여전히 async가 우수하지만 E2E는 유사
# =============================================================

# ── 1. 고지연 (800~1500ms) — 핵심 비교 ──
print_section "=== 1. 고지연 환경 (800~1500ms) ==="
run_sync  "sync-high"   "2000" "3500"
run_outbox "outbox-high" "2000" "3500"

# ── 2. 저지연 (100~300ms) — 참조 비교 ──
print_section "=== 2. 저지연 환경 (100~300ms) ==="
run_sync  "sync-low"   "100" "300"
run_outbox "outbox-low" "100" "300"

echo ""
print_info "모든 측정 완료."
echo ""
print_section "=== 결과 요약 ==="
echo "  케이스별 결과 파일: scripts/k6/results/*.json"
echo ""
print_section "=== Grafana 대시보드 안내 ==="
echo "  1. Grafana 접속: http://localhost:3000 (admin / admin123)"
echo "  2. 좌측 메뉴 → Dashboards → Import"
echo "  3. 대시보드 ID 입력: 19665 → Load → Prometheus 데이터소스 선택 → Import"
echo ""
print_info "기대 결과 (고지연 기준):"
echo "  항목              sync-high        outbox-high"
echo "  Confirm TPS:      ~70 이하         ~100"
echo "  Confirm med:      2000ms+          < 10ms    ← 핵심 지표"
echo "  E2E p95:          2000ms+          < 8000ms (Toss 처리 시간)"
