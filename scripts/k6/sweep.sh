#!/usr/bin/env bash
# sweep.sh — 부하 단계별(constant-arrival-rate) 측정 + 서버 메트릭(actuator) 샘플링.
#
# 병목 분석용: 고정 rate 를 단계적으로 올리며 각 단계의 steady-state 지표를 수집한다.
#   - k6: confirm 동기 응답 p95/p99, checks, dropped, http_req_failed, (폴링 시) e2e_completion/timeout
#   - actuator: Hikari active/pending 최대치 (관측성 풀스택 없이 직접 수집)
#
# 사용법:
#   RATES="25 50 100 150 200" SKIP_POLL=true bash scripts/k6/sweep.sh   # 동기 confirm 경로
#   RATES="25 50 75 100" SKIP_POLL=false bash scripts/k6/sweep.sh       # 비동기 e2e 경로
#
# 환경 변수:
#   BASE_URL    — k6 기저 URL (기본 http://localhost:8080)
#   RATES       — 측정할 rate(req/s) 목록 (기본 "25 50 100 150 200")
#   DURATION    — 각 단계 지속 시간 (기본 25s)
#   SKIP_POLL   — true 면 confirm 까지만(동기 경로), false 면 status 폴링 포함(e2e) (기본 true)
#   MAX_VUS / PRE_VUS — k6 VU 상한/사전할당 (기본 300/60)
#   ACTUATOR    — payment actuator/prometheus URL (기본 http://localhost:8080/actuator/prometheus)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=../common.sh
source "${ROOT_DIR}/scripts/common.sh"

BASE_URL="${BASE_URL:-http://localhost:8080}"
RATES="${RATES:-25 50 100 150 200}"
DURATION="${DURATION:-25s}"
SKIP_POLL="${SKIP_POLL:-true}"
MAX_VUS="${MAX_VUS:-300}"
PRE_VUS="${PRE_VUS:-60}"
ACTUATOR="${ACTUATOR:-http://localhost:8080/actuator/prometheus}"

# 샘플링 횟수 = duration(초) - 2 (k6 워밍업 여유)
SAMPLE_N=$(( ${DURATION%s} - 2 ))
[ "${SAMPLE_N}" -lt 1 ] && SAMPLE_N=1

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ sweep — RATES=[${RATES}] DURATION=${DURATION} SKIP_POLL=${SKIP_POLL}"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

for RATE in ${RATES}; do
    print_section "▶ RATE=${RATE} req/s"

    # actuator Hikari active/pending 1초 간격 샘플링 (백그라운드)
    SAMPLE_FILE="$(mktemp)"
    (
        for _ in $(seq 1 "${SAMPLE_N}"); do
            curl -s --max-time 2 "${ACTUATOR}" 2>/dev/null | awk '
                /^hikaricp_connections_active\{/  {a=$2}
                /^hikaricp_connections_pending\{/ {p=$2}
                /^kafka_consumer_fetch_manager_records_lag\{.*payment_events_confirmed/ {lag+=$2}
                END {if (a=="") a=0; if (p=="") p=0; print a, p, lag+0}'
            sleep 1
        done
    ) > "${SAMPLE_FILE}" &
    SAMPLER_PID=$!

    CONSTANT_RATE="${RATE}" DURATION="${DURATION}" SKIP_POLL="${SKIP_POLL}" \
    CASE_NAME="sweep-${RATE}" BASE_URL="${BASE_URL}" MAX_VUS="${MAX_VUS}" PRE_VUS="${PRE_VUS}" \
    POLL_TIMEOUT_MS="${POLL_TIMEOUT_MS:-10000}" POLL_INTERVAL_MS="${POLL_INTERVAL_MS:-500}" \
        k6 run "${SCRIPT_DIR}/async-payment.js" 2>&1 \
        | grep -E "checks\.|step:confirm |http_req_failed|dropped_iter|confirm_requests|e2e_completion|e2e_timeout" \
        | sed 's/^/    /'

    wait "${SAMPLER_PID}" 2>/dev/null
    HIK_ACTIVE_MAX="$(awk '{print $1}' "${SAMPLE_FILE}" | sort -rn | head -1)"
    HIK_PENDING_MAX="$(awk '{print $2}' "${SAMPLE_FILE}" | sort -rn | head -1)"
    LAG_MAX="$(awk '{print $3}' "${SAMPLE_FILE}" | sort -rn | head -1)"
    print_info "    Hikari active 최대=${HIK_ACTIVE_MAX} / pending 최대=${HIK_PENDING_MAX} / events.confirmed lag 최대=${LAG_MAX}"
    rm -f "${SAMPLE_FILE}"
    echo ""
done

print_info "✅ sweep 완료"
