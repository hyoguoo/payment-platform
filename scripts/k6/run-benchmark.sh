#!/bin/bash
set -e
source "$(dirname "$0")/../common.sh"

SCRIPT_DIR="$(dirname "$(realpath "$0")")"
BASE_URL="${BASE_URL:-http://host.docker.internal:8080}"

print_section "=== Phase 5: k6 Benchmark ==="
print_warning "전제 조건:"
echo "  1. Docker가 실행 중이어야 합니다"
echo "  2. 서버가 benchmark 프로파일로 실행 중이어야 합니다"
echo "     ./gradlew bootRun --args='--spring.profiles.active=benchmark'"
echo "  3. MySQL이 실행 중이어야 합니다 (docker/compose)"
echo ""

run_strategy() {
  local strategy=$1
  local script=$2
  echo ""
  print_section "--- $strategy 전략 측정 ---"
  print_warning "application.yml 에서 spring.payment.async-strategy=$strategy 로 변경 후 서버를 재기동하세요."
  if [ "$strategy" == "kafka" ]; then
    print_warning "Kafka 전략은 docker/compose/docker-compose.yml 의 Kafka 서비스도 실행 중이어야 합니다."
  fi
  read -p "준비됐으면 Enter 키를 누르세요 (Ctrl+C로 중단)..."

  docker run --rm \
    -v "${SCRIPT_DIR}:/scripts" \
    -e BASE_URL="${BASE_URL}" \
    grafana/k6 run "/scripts/${script}"

  print_info "$strategy 전략 측정 완료."
}

run_strategy "sync" "sync.js"
run_strategy "outbox" "outbox.js"
run_strategy "kafka" "kafka.js"

echo ""
print_info "모든 전략 측정 완료."
print_warning "k6 summary 수치를 BENCHMARK.md 에 기록하세요."
