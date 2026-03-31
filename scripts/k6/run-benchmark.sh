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

print_section "=== Phase 5: k6 Benchmark (Async Channel Edition) ==="
print_warning "전제 조건:"
echo "  1. Docker가 실행 중이어야 합니다"
echo "  2. docker/compose/docker-compose.yml 로 전체 스택이 기동 중이어야 합니다"
echo "  3. MySQL이 실행 중이어야 합니다 (docker/compose)"
echo ""

reset_data() {
  print_info "데이터 초기화 중 (benchmark-reset.sql)..."
  docker exec -i "${DB_CONTAINER}" mysql -u"${DB_USER}" -p"${DB_PASSWORD}" "${DB_NAME}" < "${RESET_SQL}"
  print_info "데이터 초기화 완료."
}

switch_strategy() {
  local strategy=$1
  local vt_enabled=$2
  local toss_min_delay=$3
  local toss_max_delay=$4
  local worker_count="${5:-200}"
  local channel_capacity="${6:-2000}"

  print_info "전략 전환 중: ${strategy} (VT: ${vt_enabled}, Toss: ${toss_min_delay}~${toss_max_delay}ms, Worker: ${worker_count}, Capacity: ${channel_capacity})"

  # application-benchmark.yml 및 OutboxImmediateWorker 변수 주입
  BENCHMARK_PROFILE=",benchmark" \
  ASYNC_STRATEGY="${strategy}" \
  VIRTUAL_THREADS_ENABLED="${vt_enabled}" \
  TOSS_MIN_DELAY_MILLIS="${toss_min_delay}" \
  TOSS_MAX_DELAY_MILLIS="${toss_max_delay}" \
  WORKER_COUNT="${worker_count}" \
  CHANNEL_CAPACITY="${channel_capacity}" \
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
  print_section "--- ${testid} 측정 ---"
  switch_strategy "sync" "false" "${toss_min}" "${toss_max}"
  reset_data
  run_k6 "sync.js" "${testid}"
  print_info "${testid} 완료."
}

run_outbox() {
  local testid=$1
  local vt_enabled=$2
  local toss_min=$3
  local toss_max=$4
  local worker_count="${5:-200}"
  local capacity="${6:-2000}"
  echo ""
  print_section "--- ${testid} 측정 ---"
  switch_strategy "outbox" "${vt_enabled}" "${toss_min}" "${toss_max}" "${worker_count}" "${capacity}"
  reset_data
  run_k6 "outbox.js" "${testid}"
  print_info "${testid} 완료."
}

# [Round 3] 의미 있는 벤치마크 시나리오 (Async Channel vs Sync)

# 1. 고지연 환경 (현실적 시나리오: Toss API 800~1500ms)
print_section "=== 1. 고지연 환경 (800~1500ms) ==="

# Baseline: 동기 결제
run_sync "sync-high" "800" "1500"

# PT vs VT 비교 (Worker 200개)
run_outbox "outbox-pt-w200-high" "false" "800" "1500" "200"
run_outbox "outbox-vt-w200-high" "true" "800" "1500" "200"

# Bottleneck 테스트 (Worker 부족 시나리오 - Polling Fallback 유발)
# Little's Law: 600 req/s * 1.15s = 690 workers needed
# Worker 100개만 주면 큐가 차고 offer() false가 발생하여 OutboxWorker(polling)가 처리하게 됨
run_outbox "outbox-vt-w100-high" "true" "800" "1500" "100"

# 2. 저지연 환경 (낙관적 시나리오: Toss API 100~300ms)
print_section "=== 2. 저지연 환경 (100~300ms) ==="

# Baseline: 동기 결제
run_sync "sync-low" "100" "300"

# VT 최적화 성능 (Worker 200개)
run_outbox "outbox-vt-w200-low" "true" "100" "300" "200"

# Queue Overflow 테스트 (Capacity 극단적 제한)
# 큐가 10개로 매우 작으면 순식간에 차고, 대부분이 Polling으로 넘어가서 E2E Latency가 급증하는 것을 확인
run_outbox "outbox-vt-w200-c10-low" "true" "100" "300" "200" "10"

echo ""
print_info "모든 측정 완료."
echo ""
print_section "=== Grafana 대시보드 안내 ==="
echo "  k6 결과를 Grafana에서 시각화하려면 대시보드를 임포트하세요."
echo ""
echo "  1. Grafana 접속: http://localhost:3000 (admin / admin123)"
echo "  2. 좌측 메뉴 → Dashboards → Import"
echo "  3. 'Import via grafana.com' 입력창에 대시보드 ID 입력: 19665"
echo "  4. Load → Prometheus 데이터소스 선택 → Import"
echo ""
print_info "결과: scripts/k6/results/*.json"
