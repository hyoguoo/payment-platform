#!/usr/bin/env bash
# run-benchmark.sh — 저/고지연 2환경 k6 부하 측정 오케스트레이션
#
# 용도:
#   비동기 결제 경로(checkout → confirm 202 → status 폴링)를 저지연/고지연 2환경에서
#   순차 측정하고 환경별 results JSON을 생성한다. baseline 측정(FAKE_FAIL_RATE=0).
#
# 실행 순서 (환경당):
#   1. k6 설치 확인 (미설치 시 안내 후 exit)
#   2. 의존 서비스 헬스체크 (smoke-all.sh Phase 1)
#   3. pg-service 를 해당 지연 env 로 재기동 (benchmark compose 스택)
#   4. pg-service healthy 대기
#   5. bench-seed-stock.sh 재시드 (재고 고갈 방지)
#   6. k6 실행 (CASE_NAME + --tag testid=<case>)
#   7. results/<case>.json 생성 확인
#
# 사용법:
#   bash scripts/k6/run-benchmark.sh
#   BASE_URL=http://localhost:8090 bash scripts/k6/run-benchmark.sh
#
# 환경 변수:
#   BASE_URL          — k6 요청 기저 URL (기본: http://localhost:8090 — gateway 경유)
#   FAKE_FAIL_RATE    — pg fake gateway 실패율 (기본: 0 — baseline 고정)
#   RECONCILER_TIMEOUT  — payment reconciler IN_PROGRESS 회수 기준 초 (기본: 30)
#   RECONCILER_SCAN_MS  — payment reconciler 스캔 주기 ms (기본: 15000)
#   K6_EXTRA_ARGS     — k6 run 에 추가 전달할 인자 (선택)
#
# 선행 조건:
#   - k6 설치 (https://grafana.com/docs/k6/latest/get-started/installation/)
#   - benchmark compose 스택 기동:
#     docker compose \
#       -f docker/docker-compose.infra.yml \
#       -f docker/docker-compose.apps.yml \
#       -f docker/docker-compose.observability.yml \
#       -f docker/docker-compose.benchmark.yml \
#       up -d
#   - 최초 시드(필수 아님 — 본 스크립트가 각 환경 전 재시드 수행)
#
# 결과 파일:
#   results/async-low.json  — 저지연 환경 (FAKE_LATENCY_MIN=100, FAKE_LATENCY_MAX=300)
#   results/async-high.json — 고지연 환경 (FAKE_LATENCY_MIN=800, FAKE_LATENCY_MAX=1500)
#
# 주의:
#   - FAKE_FAIL_RATE=0 고정 (baseline — QUARANTINED 발생 방지)
#   - pg-service 재기동 시 benchmark compose 4-파일 조합을 사용한다
#   - Grafana 필터: testid 태그로 저/고 환경 구분 가능

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# shellcheck source=../common.sh
source "${ROOT_DIR}/scripts/common.sh"

# ---------------------------------------------------------------------------
# 환경 변수 기본값
# ---------------------------------------------------------------------------

BASE_URL="${BASE_URL:-http://localhost:8090}"   # gateway 경유(2 인스턴스 lb:// 분산)
FAKE_FAIL_RATE="${FAKE_FAIL_RATE:-0}"
RECONCILER_TIMEOUT="${RECONCILER_TIMEOUT:-30}"
RECONCILER_SCAN_MS="${RECONCILER_SCAN_MS:-15000}"
K6_EXTRA_ARGS="${K6_EXTRA_ARGS:-}"

# benchmark compose 4-파일 조합
INFRA_COMPOSE="${ROOT_DIR}/docker/docker-compose.infra.yml"
APPS_COMPOSE="${ROOT_DIR}/docker/docker-compose.apps.yml"
OBS_COMPOSE="${ROOT_DIR}/docker/docker-compose.observability.yml"
BENCH_COMPOSE="${ROOT_DIR}/docker/docker-compose.benchmark.yml"

COMPOSE_ALL="-f ${INFRA_COMPOSE} -f ${APPS_COMPOSE} -f ${OBS_COMPOSE} -f ${BENCH_COMPOSE}"

# results 디렉토리 (k6 run 시작 디렉토리 기준으로 생성됨)
RESULTS_DIR="${ROOT_DIR}/results"

# ---------------------------------------------------------------------------
# 헬퍼 함수
# ---------------------------------------------------------------------------

# pg-service healthy 대기
# $1: 타임아웃(초)
wait_pg_healthy() {
    local timeout_sec="${1:-120}"
    local deadline=$(( $(date +%s) + timeout_sec ))

    print_warning "pg-service healthy 대기(최대 ${timeout_sec}초)..."

    while :; do
        # 앱 서비스는 container_name 미지정(scale-able)이라 docker-pg-service-1 형식 —
        # 하드코딩 대신 compose ps -q 로 컨테이너 ID 를 동적으로 얻는다.
        local cid status
        cid=$(docker compose ${COMPOSE_ALL} ps -q pg-service 2>/dev/null | head -1)
        status="absent"
        if [[ -n "${cid}" ]]; then
            status=$(docker inspect -f '{{.State.Health.Status}}' "${cid}" 2>/dev/null || echo "absent")
        fi

        if [[ "${status}" == "healthy" ]]; then
            print_info "  ✅ pg-service healthy"
            return 0
        fi

        if (( $(date +%s) > deadline )); then
            print_error "  ❌ pg-service ${timeout_sec}초 내 healthy 실패 (현재: ${status})"
            [[ -n "${cid}" ]] && docker inspect -f '{{range .State.Health.Log}}{{.Output}}{{end}}' "${cid}" 2>/dev/null | tail -5 || true
            return 1
        fi

        sleep 3
        echo -n "."
    done
    echo
}

# payment-service force-recreate — RECONCILER_TIMEOUT 등 env 반영 보장.
# benchmark compose 가 RECONCILER_IN_FLIGHT_TIMEOUT_SECONDS 를 env 로 주입하지만,
# run-benchmark.sh 가 payment-service 를 force-recreate 하지 않으면 host 에서
# export 한 RECONCILER_TIMEOUT 값이 기존 컨테이너에 반영되지 않는다.
restart_payment_with_reconciler() {
    print_section "▶ payment-service 재기동 (RECONCILER_TIMEOUT=${RECONCILER_TIMEOUT}s, RECONCILER_SCAN_MS=${RECONCILER_SCAN_MS}ms)"

    RECONCILER_TIMEOUT="${RECONCILER_TIMEOUT}" \
    RECONCILER_SCAN_MS="${RECONCILER_SCAN_MS}" \
    docker compose ${COMPOSE_ALL} up -d --no-build --force-recreate payment-service

    # payment-service healthy 대기 — 최대 120초(healthcheck start_period 60s 포함)
    local deadline=$(( $(date +%s) + 120 ))
    print_warning "payment-service healthy 대기(최대 120초)..."
    while :; do
        local cid status
        cid=$(docker compose ${COMPOSE_ALL} ps -q payment-service 2>/dev/null | head -1)
        status="absent"
        if [[ -n "${cid}" ]]; then
            status=$(docker inspect -f '{{.State.Health.Status}}' "${cid}" 2>/dev/null || echo "absent")
        fi
        if [[ "${status}" == "healthy" ]]; then
            print_info "  ✅ payment-service healthy"
            return 0
        fi
        if (( $(date +%s) > deadline )); then
            print_error "  ❌ payment-service 120초 내 healthy 실패 (현재: ${status})"
            [[ -n "${cid}" ]] && docker inspect -f '{{range .State.Health.Log}}{{.Output}}{{end}}' "${cid}" 2>/dev/null | tail -5 || true
            return 1
        fi
        sleep 3
        echo -n "."
    done
    echo
}

# pg-service 재기동 (지연 env 주입)
# $1: FAKE_LATENCY_MIN, $2: FAKE_LATENCY_MAX
restart_pg_with_latency() {
    local lat_min="${1}"
    local lat_max="${2}"

    print_section "▶ pg-service 재기동 (latency min=${lat_min}ms, max=${lat_max}ms, failRate=${FAKE_FAIL_RATE})"

    FAKE_LATENCY_MIN="${lat_min}" \
    FAKE_LATENCY_MAX="${lat_max}" \
    FAKE_FAIL_RATE="${FAKE_FAIL_RATE}" \
    RECONCILER_TIMEOUT="${RECONCILER_TIMEOUT}" \
    RECONCILER_SCAN_MS="${RECONCILER_SCAN_MS}" \
    docker compose ${COMPOSE_ALL} up -d --no-build --force-recreate pg-service

    wait_pg_healthy 120
}

# bench-seed-stock.sh 재시드
run_seed() {
    print_section "▶ bench-seed-stock 재시드"
    bash "${ROOT_DIR}/scripts/bench-seed-stock.sh"
}

# k6 단일 환경 실행
# $1: CASE_NAME (결과 파일명 결정), $2: testid 태그값
run_k6_case() {
    local case_name="${1}"
    local testid="${2}"
    local result_file="${RESULTS_DIR}/${case_name}.json"

    print_section "▶ k6 실행 — CASE_NAME=${case_name}, testid=${testid}"

    # results 디렉토리 생성 (없으면)
    mkdir -p "${RESULTS_DIR}"

    # k6는 handleSummary의 outputPath(results/<caseName>.json)를 실행 CWD 기준으로 생성한다.
    # ROOT_DIR 에서 실행해야 results/ 위치가 일치한다.
    # k6 threshold 위반(exit 99)은 baseline 탐색 단계에서 정상이므로 측정을 중단시키지 않는다.
    # 진짜 실행 오류(다른 exit code)만 실패로 처리한다.
    set +e
    (
        cd "${ROOT_DIR}"
        k6 run \
            --tag "testid=${testid}" \
            -e "BASE_URL=${BASE_URL}" \
            -e "CASE_NAME=${case_name}" \
            ${K6_EXTRA_ARGS} \
            "${SCRIPT_DIR}/async-payment.js"
    )
    local k6_exit=$?
    set -e

    if [[ ${k6_exit} -ne 0 && ${k6_exit} -ne 99 ]]; then
        print_error "❌ k6 실행 오류 (exit ${k6_exit})"
        return 1
    fi
    if [[ ${k6_exit} -eq 99 ]]; then
        print_warning "⚠ k6 threshold 위반(exit 99) — 측정 결과는 생성됨(baseline 탐색 단계, 계속 진행)"
    fi

    if [[ -f "${result_file}" ]]; then
        print_info "✅ 결과 파일 생성: ${result_file}"
    else
        print_error "❌ 결과 파일 없음: ${result_file} — handleSummary 확인 필요"
        return 1
    fi
}

# ---------------------------------------------------------------------------
# 사전 확인
# ---------------------------------------------------------------------------

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ run-benchmark.sh — 저/고지연 2환경 k6 부하 측정 오케스트레이션"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# 1. k6 설치 확인
if ! command -v k6 >/dev/null 2>&1; then
    print_error "❌ k6 가 설치되어 있지 않습니다."
    echo ""
    echo "  설치 방법:"
    echo "    macOS:  brew install k6"
    echo "    Linux:  https://grafana.com/docs/k6/latest/get-started/installation/#linux"
    echo "    기타:   https://grafana.com/docs/k6/latest/get-started/installation/"
    echo ""
    echo "  설치 후 본 스크립트를 다시 실행하세요."
    exit 1
fi
print_info "✅ k6 설치 확인: $(k6 version)"

# 2. Docker 데몬 확인
check_docker

# 3. 의존 서비스 헬스체크 (smoke-all Phase 1)
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ Phase 1 — 의존 서비스 헬스체크 (smoke-all.sh Phase 1)"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if ! bash "${ROOT_DIR}/scripts/smoke-all.sh"; then
    print_error "❌ 헬스체크 실패 — 스택이 완전히 기동되지 않았습니다."
    echo ""
    echo "  benchmark 스택 기동:"
    echo "    docker compose \\"
    echo "      -f docker/docker-compose.infra.yml \\"
    echo "      -f docker/docker-compose.apps.yml \\"
    echo "      -f docker/docker-compose.observability.yml \\"
    echo "      -f docker/docker-compose.benchmark.yml \\"
    echo "      up -d"
    exit 1
fi
echo ""

# ---------------------------------------------------------------------------
# 사전 기동: payment-service force-recreate (RECONCILER_TIMEOUT 실제 반영)
# ---------------------------------------------------------------------------
# benchmark compose 가 RECONCILER_IN_FLIGHT_TIMEOUT_SECONDS 를 env 로 주입하지만,
# 스택 최초 기동 시 host 의 RECONCILER_TIMEOUT export 가 반영되지 않을 수 있다.
# 측정 전 payment-service 를 force-recreate 해 600s(또는 지정값) 실제 주입을 보장한다.
# (케이스 간 pg-service 만 재기동하면 payment-service 는 1회 재기동으로 양 케이스에 공유됨)
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ 사전 기동 — payment-service RECONCILER_TIMEOUT 반영"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

restart_payment_with_reconciler

echo ""

# ---------------------------------------------------------------------------
# Case 1: 저지연 환경
# ---------------------------------------------------------------------------

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ Case 1/2 — 저지연 환경 (async-low)"
print_section "  pg latency: 100~300ms / failRate: ${FAKE_FAIL_RATE}"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# 저지연 pg 재기동
restart_pg_with_latency 100 300

# 재시드
run_seed

# k6 실행
run_k6_case "async-low" "async-low"

echo ""

# ---------------------------------------------------------------------------
# Case 2: 고지연 환경
# ---------------------------------------------------------------------------

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ Case 2/2 — 고지연 환경 (async-high)"
print_section "  pg latency: 800~1500ms / failRate: ${FAKE_FAIL_RATE}"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# 고지연 pg 재기동
restart_pg_with_latency 800 1500

# 재시드
run_seed

# k6 실행
run_k6_case "async-high" "async-high"

echo ""

# ---------------------------------------------------------------------------
# 완료 요약
# ---------------------------------------------------------------------------

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_info "✅ 저/고지연 2환경 측정 완료"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "  결과 파일:"
echo "    - ${RESULTS_DIR}/async-low.json  (저지연: 100~300ms)"
echo "    - ${RESULTS_DIR}/async-high.json (고지연: 800~1500ms)"
echo ""
echo "  Grafana 필터: testid=async-low / testid=async-high"
echo ""
echo "  교차 검증 (settle 대기 후):"
echo "    bash scripts/k6/verify-settlement.sh"
