#!/usr/bin/env bash
# alert-rules-promtool.sh — 알람 규칙 4그룹 promtool 발화 단정 검증.
#
# 목적:
#   Prometheus 알람 규칙 4그룹(코디네이터 정체 / 종결 가드 skip / DLQ 적체 / 가용성)의
#   발화 로직을 합성 시계열 픽스처로 단정한다.
#   라이브 스택 없이 Docker 만으로 실행 가능. 총 26 케이스.
#
# ── 검증 픽스처 ────────────────────────────────────────────────────────────
#   코디네이터 정체 (6 케이스): observability/prometheus/rules/tests/coordinator_test.yml
#     (a) txn abort rate 급증 → KafkaCoordinatorTxnAbortRising FIRING
#     (b) consumer lag 급증 → KafkaCoordinatorLagHigh FIRING
#     (c1) up==0 → KafkaBrokerUnavailable FIRING
#     (c2) kafka_brokers<1 → KafkaBrokerUnavailable FIRING
#     (c3) kafka_brokers 시리즈 absent → absent() 분기 KafkaBrokerUnavailable FIRING
#     (d) 정상 baseline abort rate → no alert (알람 피로 방지 회귀 고정)
#
#   종결 가드 skip (3 케이스): observability/prometheus/rules/tests/guard_skip_test.yml
#     (a) 위험 status skip 비율 20% → GuardSkipDangerousStatusHigh FIRING
#     (b) DONE-only skip (정상 재발행 경로) → no alert
#     (c) 저트래픽 (floor 미충족) → no alert (0-division 흡수 회귀 고정)
#
#   DLQ 적체 (8 케이스): observability/prometheus/rules/tests/dlq_test.yml
#     (a) 앱 카운터 increase>0 → DlqAppCounterRising FIRING
#     (b) .dlq offset increase>0 → DlqTopicOffsetRising FIRING
#     (c) 정상 (델타 0) → 3개 알람 모두 미발화
#     (d) 앱 카운터만↑ (offset 없음) → DlqAppCounterRising만 FIRING (독립 회귀 고정)
#     (e) offset만↑ (앱 카운터 없음) → DlqTopicOffsetRising만 FIRING (독립 회귀 고정)
#     (f) commands.confirm.dlq 컨슈머 lag 잔존 → DlqCommandsConsumerLag FIRING
#     (g) pg_retry_exhausted_quarantine_total만↑ (payment_eos 평탄) → DlqAppCounterRising FIRING (pg 분기)
#
#   가용성 (9 케이스): observability/prometheus/rules/tests/availability_test.yml
#     (a1) up==0 → ServiceDown FIRING (for:1m 충족)
#     (a2) 정상(up=1) → ServiceDown 미발화
#     (b) dependency_up{component=db}==0 → DependencyDown FIRING
#     (c) redis-stock 단독 다운 → DependencyDown FIRING, redis-dedupe 미발화
#     (d1) staleness 조건 → DependencyHealthStale FIRING
#     (d2) 폴러 정상(최근 갱신) → DependencyHealthStale 미발화
#     (e1) dependency_up 시리즈 absent → absent() 백스톱 FIRING
#     (e2) last_poll 시리즈 absent → absent() 백스톱 FIRING
#     (f) 정상 baseline → 3알람 모두 미발화
#
# ── 선행 조건 ──────────────────────────────────────────────────────────────
#   Docker 기동 (라이브 Prometheus / Kafka 불필요)
#
# ── 사용법 ─────────────────────────────────────────────────────────────────
#   ./scripts/smoke/alert-rules-promtool.sh
#
# ── 종료 코드 ──────────────────────────────────────────────────────────────
#   0 — 25 케이스 전체 PASS
#   1 — 실패 또는 Docker 미기동

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=../common.sh
source "${ROOT_DIR}/scripts/common.sh"

PROM_IMAGE="${PROM_IMAGE:-prom/prometheus:v2.51.2}"

# ── Docker 가용 확인 ────────────────────────────────────────────────────────
if ! docker info > /dev/null 2>&1; then
    print_error "❌ Docker 미기동 — promtool 실행 불가"
    exit 1
fi

print_section "════════════════════════════════════════════════════════════"
print_section "  alert-rules-promtool — 알람 규칙 4그룹 발화 단정 (25 케이스)"
print_section "════════════════════════════════════════════════════════════"
print_warning "  이미지: ${PROM_IMAGE}"
print_warning "  마운트: ${ROOT_DIR}/observability/prometheus → /work"
echo ""

PASS_COUNT=0
FAIL_COUNT=0

# run_test <그룹 레이블> <픽스처 파일명>
run_test() {
    local group="$1"
    local fixture="$2"
    print_section "▶ ${group} — ${fixture}"
    if docker run --rm --entrypoint /bin/promtool \
        -v "${ROOT_DIR}/observability/prometheus:/work" \
        "${PROM_IMAGE}" \
        test rules "/work/rules/tests/${fixture}"; then
        print_info "  ✅ ${group} PASS"
        PASS_COUNT=$((PASS_COUNT + 1))
    else
        print_error "  ❌ ${group} FAIL"
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
    echo ""
}

run_test "코디네이터 정체 (6 케이스)" "coordinator_test.yml"
run_test "종결 가드 skip (3 케이스)" "guard_skip_test.yml"
run_test "DLQ 적체 (7 케이스)" "dlq_test.yml"
run_test "가용성 (9 케이스)" "availability_test.yml"

# ── 종합 결과 ───────────────────────────────────────────────────────────────
print_section "════════════════════════════════════════════════════════════"
if [ "${FAIL_COUNT}" -eq 0 ]; then
    print_info "✅ 알람 규칙 promtool test 전체 PASS (4 그룹, 25 케이스)"
    exit 0
else
    print_error "❌ 알람 규칙 promtool test FAIL (실패 그룹 ${FAIL_COUNT}개)"
    print_error "   규칙 파일 확인:"
    print_error "     observability/prometheus/rules/coordinator.yml"
    print_error "     observability/prometheus/rules/guard-skip.yml"
    print_error "     observability/prometheus/rules/dlq.yml"
    print_error "     observability/prometheus/rules/availability.yml"
    exit 1
fi
