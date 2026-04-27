#!/usr/bin/env bash
# smoke-all.sh — 모든 smoke 스크립트를 순차 실행하는 단일 entry point.
#
# Phase 1 (트래픽 무관 — 항상 실행):
#   1) scripts/smoke/infra-healthcheck.sh
#      · 13 컨테이너 health + 9 호스트 포트 + 5 Eureka 등록
#   2) scripts/smoke/kafka-topic-config.sh
#      · 토픽 partition / replication-factor / retry 토픽 부재 검증
#
# Phase 2 (트래픽 의존 — --with-trace 옵션 시):
#   3) scripts/smoke/trace-header-check.sh
#      · payment.commands.confirm 토픽의 traceparent 헤더 주입 확인
#   4) scripts/smoke/trace-continuity-check.sh
#      · gateway → payment → pg → product/user 다중 홉 traceId 연속성
#
# 사용법:
#   bash scripts/smoke-all.sh                  # Phase 1 만
#   bash scripts/smoke-all.sh --with-trace     # Phase 1 + Phase 2 (결제 1건 선행 필요)
#
# 종료 코드:
#   0 — 모든 단계 PASS
#   1 — 어느 단계든 FAIL (fail-fast)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=common.sh
source "${ROOT_DIR}/scripts/common.sh"

WITH_TRACE=false
for arg in "$@"; do
    case "${arg}" in
        --with-trace) WITH_TRACE=true ;;
        -h|--help)
            sed -n '2,21p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            print_error "알 수 없는 옵션: ${arg}"
            exit 2
            ;;
    esac
done

run_step() {
    local label="$1"
    local script="$2"
    print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    print_section "▶ ${label}"
    print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    if bash "${script}"; then
        print_info "✅ ${label} PASS"
        return 0
    else
        print_error "❌ ${label} FAIL — fail-fast 종료"
        return 1
    fi
}

# ─────────────────────────────────────────────
# Phase 1 — 항상 실행
# ─────────────────────────────────────────────
run_step "Phase 1.1 — infra healthcheck" "${ROOT_DIR}/scripts/smoke/infra-healthcheck.sh" || exit 1
echo ""
run_step "Phase 1.2 — kafka topic config" "${ROOT_DIR}/scripts/smoke/kafka-topic-config.sh" || exit 1

# ─────────────────────────────────────────────
# Phase 2 — 트래픽 의존 (--with-trace 시만)
# ─────────────────────────────────────────────
if [[ "${WITH_TRACE}" == "true" ]]; then
    echo ""
    run_step "Phase 2.1 — kafka header traceparent" "${ROOT_DIR}/scripts/smoke/trace-header-check.sh" || exit 1
    echo ""
    run_step "Phase 2.2 — trace continuity (다중 홉)" "${ROOT_DIR}/scripts/smoke/trace-continuity-check.sh" || exit 1
fi

# ─────────────────────────────────────────────
# 종합
# ─────────────────────────────────────────────
echo ""
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
if [[ "${WITH_TRACE}" == "true" ]]; then
    print_info "✅ smoke-all (Phase 1 + 2) 전 단계 PASS"
else
    print_info "✅ smoke-all (Phase 1) 전 단계 PASS"
    print_warning "ℹ Phase 2 (traceparent 헤더 / 다중 홉 연속성) 검증은 결제 1건 발생 후 --with-trace 로 실행"
fi
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
