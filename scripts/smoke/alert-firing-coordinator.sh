#!/usr/bin/env bash
# alert-firing-coordinator.sh — 코디네이터 정체 알람 라이브 발화 검증.
#
# 목적:
#   Toxiproxy latency toxic 주입 후 Prometheus /api/v1/alerts 를 폴링해
#   코디네이터 알람(KafkaCoordinatorTxnAbortRising / KafkaCoordinatorLagHigh /
#   KafkaBrokerUnavailable) 발화를 검증한다.
#
#   단일 broker 환경에서 payment-service 가 producer 이기도 하여 latency 주입 시
#   유입도 동반 감소하므로 consumer lag 가 임계를 결정적으로 초과하기 어렵다.
#   txn abort 도 주입 지연이 transaction.timeout.ms 미만이면 발화하지 않는다.
#   이 한계로 라이브 결정적 발화가 불가한 경우 promtool test rules 픽스처로 격하한다
#   (규칙 자체는 Prometheus 라이브 로드 + 운영 유효).
#
# ── 선행 조건 (라이브 경로) ───────────────────────────────────────────────
#   docker compose \
#     -f docker/docker-compose.infra.yml \
#     -f docker/docker-compose.apps.yml \
#     -f docker/docker-compose.observability.yml \
#     -f docker/docker-compose.drill.yml \
#     up -d
#   scripts/smoke/create-topics.sh
#
# ── 사용법 ─────────────────────────────────────────────────────────────────
#   ./scripts/smoke/alert-firing-coordinator.sh                  # 라이브 시도 후 격하 폴백
#   ./scripts/smoke/alert-firing-coordinator.sh --fallback-only  # 격하 폴백 직행
#
# ── 종료 코드 ──────────────────────────────────────────────────────────────
#   0 — PASS (라이브 발화 확인 또는 promtool test rules pass)
#   1 — FAIL (promtool test 실패 또는 docker 없음)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=../common.sh
source "${ROOT_DIR}/scripts/common.sh"

# ── 설정 (환경변수 오버라이드 가능) ────────────────────────────────────────
PROM_URL="${PROM_URL:-http://localhost:9090}"
TOXI_HOST="${TOXI_HOST:-localhost}"
TOXI_ADMIN_PORT="${TOXI_ADMIN_PORT:-8474}"
TOXI_ADMIN_URL="http://${TOXI_HOST}:${TOXI_ADMIN_PORT}"

# 라이브 폴링 타임아웃 — latency 주입 후 알람 for:1m 충족까지 여유 포함
POLL_TIMEOUT_S="${POLL_TIMEOUT_S:-120}"
POLL_INTERVAL_S="${POLL_INTERVAL_S:-5}"

# Prometheus 이미지 버전 — promtool 실행에 사용
PROM_IMAGE="${PROM_IMAGE:-prom/prometheus:v2.51.2}"

# ── 격하 폴백 플래그 ────────────────────────────────────────────────────────
FALLBACK_ONLY=false
for arg in "$@"; do
    [ "${arg}" = "--fallback-only" ] && FALLBACK_ONLY=true
done

# ── toxic 정리 트랩 (비정상 종료 시에도 해제 시도) ─────────────────────────
TOXIC_INJECTED=false

cleanup_toxic() {
    if [ "${TOXIC_INJECTED}" = "true" ]; then
        print_section "▶ latency toxic 정리 (종료 훅)"
        "${SCRIPT_DIR}/drill-toxiproxy.sh" remove 2>/dev/null || true
        TOXIC_INJECTED=false
    fi
}
trap cleanup_toxic EXIT INT TERM

# ── 헬퍼 함수 ──────────────────────────────────────────────────────────────

# Prometheus API 응답 여부 확인 (미기동 시 false 반환, 에러 아님)
check_prometheus_available() {
    curl -sf -m 5 "${PROM_URL}/-/ready" > /dev/null 2>&1
}

# Toxiproxy admin API 응답 여부 확인 (미기동 시 false 반환, 에러 아님)
check_toxiproxy_available() {
    curl -sf -m 5 "${TOXI_ADMIN_URL}/proxies" > /dev/null 2>&1
}

# Prometheus /api/v1/alerts 에서 코디네이터 알람 firing 상태 확인
# 반환: 0 = firing 알람 존재, 1 = 없음
check_coordinator_firing() {
    local alerts_json
    alerts_json=$(curl -sf -m 10 "${PROM_URL}/api/v1/alerts" 2>/dev/null || echo "")
    [ -z "${alerts_json}" ] && return 1

    local firing_count
    firing_count=$(echo "${alerts_json}" | python3 -c "
import sys, json
data = json.load(sys.stdin)
alerts = data.get('data', {}).get('alerts', [])
names = {'KafkaCoordinatorTxnAbortRising', 'KafkaCoordinatorLagHigh', 'KafkaBrokerUnavailable'}
firing = [a for a in alerts if a.get('state') == 'firing' and a['labels'].get('alertname', '') in names]
print(len(firing))
for a in firing:
    print('  FIRING:', a['labels'].get('alertname'), '|', a['labels'], file=sys.stderr)
" 2>/dev/null || echo "0")

    [ "${firing_count:-0}" -gt 0 ]
}

# Prometheus /api/v1/alerts 폴링 — 코디네이터 알람 firing 대기
# 반환: 0 = 발화 감지, 1 = 타임아웃
poll_coordinator_alerts() {
    local elapsed=0
    print_section "▶ 코디네이터 알람 발화 폴링 (타임아웃 ${POLL_TIMEOUT_S}s, 간격 ${POLL_INTERVAL_S}s)"
    print_warning "  → 폴링 중 (. 가 찍히면 아직 미발화)"

    while [ "${elapsed}" -lt "${POLL_TIMEOUT_S}" ]; do
        if check_coordinator_firing; then
            echo ""
            print_info "[FIRING] 코디네이터 알람 발화 감지 (elapsed=${elapsed}s)"
            return 0
        fi
        echo -n "."
        sleep "${POLL_INTERVAL_S}"
        elapsed=$((elapsed + POLL_INTERVAL_S))
    done

    echo ""
    return 1
}

# promtool test rules 실행 (docker 경유) — 코디네이터 픽스처
run_promtool_coordinator() {
    print_section "▶ promtool test rules — 코디네이터 픽스처 (docker 경유)"
    print_warning "  이미지: ${PROM_IMAGE}"
    print_warning "  픽스처: observability/prometheus/rules/tests/coordinator_test.yml"
    echo ""

    if ! docker info > /dev/null 2>&1; then
        print_error "[FAIL] Docker 미기동 — promtool 실행 불가"
        return 1
    fi

    docker run --rm --entrypoint /bin/promtool \
        -v "${ROOT_DIR}/observability/prometheus:/work" \
        "${PROM_IMAGE}" \
        test rules /work/rules/tests/coordinator_test.yml
}

# ── 라이브 발화 시도 경로 ─────────────────────────────────────────────────
try_live_firing() {
    print_section "═══════════════════════════════════════════════════════"
    print_section "  코디네이터 알람 라이브 발화 시도"
    print_section "═══════════════════════════════════════════════════════"

    # Toxiproxy + Prometheus 가용 확인
    if ! check_toxiproxy_available; then
        print_warning "[SKIP] Toxiproxy admin API 응답 없음 — 격하 폴백으로 전환"
        print_warning "  드릴 프로파일 기동 확인:"
        print_warning "    docker compose -f docker/docker-compose.infra.yml \\"
        print_warning "      -f docker/docker-compose.apps.yml \\"
        print_warning "      -f docker/docker-compose.observability.yml \\"
        print_warning "      -f docker/docker-compose.drill.yml up -d"
        return 1
    fi

    if ! check_prometheus_available; then
        print_warning "[SKIP] Prometheus API 응답 없음 — 격하 폴백으로 전환"
        print_warning "  observability 스택 포함 기동 확인:"
        print_warning "    docker compose ... -f docker/docker-compose.observability.yml up -d"
        return 1
    fi

    print_info "[OK] Toxiproxy admin API + Prometheus API 응답 확인"
    echo ""

    # latency toxic 주입
    print_section "▶ latency toxic 주입"
    "${SCRIPT_DIR}/drill-toxiproxy.sh" inject
    TOXIC_INJECTED=true
    echo ""

    # 코디네이터 알람 발화 폴링
    if poll_coordinator_alerts; then
        # 발화 확인 → 정리 후 PASS
        print_section "▶ latency toxic 해제"
        "${SCRIPT_DIR}/drill-toxiproxy.sh" remove
        TOXIC_INJECTED=false
        echo ""
        print_info "✅ 라이브 발화 PASS — 코디네이터 알람 발화 확인"
        return 0
    else
        # 타임아웃 → 격하 폴백으로 전환 (정리는 EXIT 트랩이 처리)
        print_warning ""
        print_warning "[TIMEOUT] 코디네이터 알람 미발화 (${POLL_TIMEOUT_S}s 경과)"
        print_warning "  단일 broker 비대칭 한계:"
        print_warning "  - payment-service 가 producer 이기도 하여 latency 주입 시 유입도 동반 감소"
        print_warning "    → consumer lag 가 임계(KafkaCoordinatorLagHigh: 1000 messages)를 결정적으로 초과하기 어려움"
        print_warning "  - txn abort 는 주입 지연이 transaction.timeout.ms 미만이면 발화하지 않음"
        print_warning "    (실측: latency 2000ms 주입, lag 피크 ~150, abort 미발화)"
        print_warning "  규칙은 Prometheus 라이브 로드 + 운영 유효 — 발화 로직은 promtool 픽스처로 검증"
        echo ""
        print_section "▶ latency toxic 해제"
        "${SCRIPT_DIR}/drill-toxiproxy.sh" remove
        TOXIC_INJECTED=false
        echo ""
        return 1
    fi
}

# ── 메인 ─────────────────────────────────────────────────────────────────────
print_section "════════════════════════════════════════════════════════════"
print_section "  alert-firing-coordinator — 코디네이터 정체 알람 발화 검증"
print_section "════════════════════════════════════════════════════════════"
echo ""

LIVE_PASSED=false

if [ "${FALLBACK_ONLY}" = "false" ]; then
    if try_live_firing; then
        LIVE_PASSED=true
    fi
fi

if [ "${LIVE_PASSED}" = "true" ]; then
    exit 0
fi

# ── 격하 폴백: promtool test rules ────────────────────────────────────────
print_section "════════════════════════════════════════════════════════════"
print_section "  격하 폴백 — promtool test rules (코디네이터 픽스처)"
print_section "════════════════════════════════════════════════════════════"
print_warning "  라이브 발화 환경 한계 또는 스택 미기동으로 격하 폴백 실행"
print_warning "  규칙은 Prometheus 라이브 로드 + 운영 유효."
print_warning "  promtool 합성 시계열 5케이스:"
print_warning "    (a) txn abort 급증 → KafkaCoordinatorTxnAbortRising FIRING"
print_warning "    (b) consumer lag 급증 → KafkaCoordinatorLagHigh FIRING"
print_warning "    (c1) up==0 → KafkaBrokerUnavailable FIRING"
print_warning "    (c2) kafka_brokers<1 → KafkaBrokerUnavailable FIRING"
print_warning "    (d) 정상 baseline abort rate → no alert (알람 피로 방지 회귀 고정)"
echo ""

if run_promtool_coordinator; then
    echo ""
    print_info "✅ 격하 폴백 PASS — promtool test rules 5케이스 통과"
    print_info "   규칙 발화 로직 검증 완료. 라이브 발화는 환경 한계로 제외."
    exit 0
else
    echo ""
    print_error "❌ 격하 폴백 FAIL — promtool test rules 실패"
    print_error "   규칙 파일 또는 픽스처 오류 확인:"
    print_error "     observability/prometheus/rules/coordinator.yml"
    print_error "     observability/prometheus/rules/tests/coordinator_test.yml"
    exit 1
fi
