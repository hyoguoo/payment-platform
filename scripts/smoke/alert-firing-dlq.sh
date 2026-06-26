#!/usr/bin/env bash
# alert-firing-dlq.sh — DLQ 적체 알람 발화 검증.
#
# 목적:
#   DLQ 알람 규칙(DlqAppCounterRising / DlqTopicOffsetRising / DlqCommandsConsumerLag)
#   발화를 검증한다.
#
#   두 DLQ 도달 경로 모두 단일 broker 환경에서 라이브 결정적 발화가 불가하다.
#
#   - EOS 경로 (payment): broker latency 하 EOS commit timeout → abort → backoff 소진 → DLQ 도달.
#     latency 주입 지연이 transaction.timeout.ms 미만이면 commit timeout 이 발화하지 않는다
#     (실측: latency 2000ms 주입, abort 미발화).
#
#   - pg 경로 (pg-service): 벤더 HTTP toxic → retryable 에러 → attempt≥4 격리 → QUARANTINED.
#     pg 경로 toxic 은 드릴 compose 구성에 포함되지 않으며 실 벤더 sandbox 인증이 전제된다
#     (기본 환경은 FakePgGatewayStrategy 우회 또는 secret 미설정).
#
#   → 격하 폴백 디폴트: promtool test rules dlq 픽스처 실행 + 통합테스트 위임.
#
# ── 격하 폴백 경로 ─────────────────────────────────────────────────────────
#   EOS 경로: PaymentEosIntegrationTest
#     — EOS commit 실패 → AfterRollbackProcessor → DLQ 도달 + payment_eos_commit_failure_dlq_total
#   pg 경로:  PgSelfLoopRetryExhaustionIntegrationTest
#     — pg retry 소진(attempt≥4) → QUARANTINED + pg_retry_exhausted_quarantine_total
#
# ── 사용법 ─────────────────────────────────────────────────────────────────
#   ./scripts/smoke/alert-firing-dlq.sh          # 격하 폴백 (기본)
#   ./scripts/smoke/alert-firing-dlq.sh --live   # 라이브 폴링 추가 시도 (환경 가용 시)
#
# ── 종료 코드 ──────────────────────────────────────────────────────────────
#   0 — PASS (promtool test rules pass)
#   1 — FAIL (promtool test 실패 또는 docker 없음)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=../common.sh
source "${ROOT_DIR}/scripts/common.sh"

# ── 설정 (환경변수 오버라이드 가능) ────────────────────────────────────────
PROM_URL="${PROM_URL:-http://localhost:9090}"

# 라이브 폴링 타임아웃 (--live 모드에서만 사용)
POLL_TIMEOUT_S="${POLL_TIMEOUT_S:-180}"
POLL_INTERVAL_S="${POLL_INTERVAL_S:-5}"

# Prometheus 이미지 버전 — promtool 실행에 사용
PROM_IMAGE="${PROM_IMAGE:-prom/prometheus:v2.51.2}"

# ── 라이브 폴링 플래그 ──────────────────────────────────────────────────────
LIVE_MODE=false
for arg in "$@"; do
    [ "${arg}" = "--live" ] && LIVE_MODE=true
done

# ── 헬퍼 함수 ──────────────────────────────────────────────────────────────

# Prometheus API 응답 여부 확인 (미기동 시 false 반환, 에러 아님)
check_prometheus_available() {
    curl -sf -m 5 "${PROM_URL}/-/ready" > /dev/null 2>&1
}

# Prometheus /api/v1/alerts 에서 DLQ 알람 firing 상태 확인
# 반환: 0 = firing 알람 존재, 1 = 없음
check_dlq_firing() {
    local alerts_json
    alerts_json=$(curl -sf -m 10 "${PROM_URL}/api/v1/alerts" 2>/dev/null || echo "")
    [ -z "${alerts_json}" ] && return 1

    local firing_count
    firing_count=$(echo "${alerts_json}" | python3 -c "
import sys, json
data = json.load(sys.stdin)
alerts = data.get('data', {}).get('alerts', [])
names = {'DlqAppCounterRising', 'DlqTopicOffsetRising', 'DlqCommandsConsumerLag'}
firing = [a for a in alerts if a.get('state') == 'firing' and a['labels'].get('alertname', '') in names]
print(len(firing))
for a in firing:
    print('  FIRING:', a['labels'].get('alertname'), '|', a['labels'], file=sys.stderr)
" 2>/dev/null || echo "0")

    [ "${firing_count:-0}" -gt 0 ]
}

# Prometheus /api/v1/alerts 폴링 — DLQ 알람 firing 대기 (--live 모드 전용)
# 반환: 0 = 발화 감지, 1 = 타임아웃
poll_dlq_alerts() {
    local elapsed=0
    print_section "▶ DLQ 알람 발화 폴링 (타임아웃 ${POLL_TIMEOUT_S}s, 간격 ${POLL_INTERVAL_S}s)"
    print_warning "  → 폴링 중 (. 가 찍히면 아직 미발화)"

    while [ "${elapsed}" -lt "${POLL_TIMEOUT_S}" ]; do
        if check_dlq_firing; then
            echo ""
            print_info "[FIRING] DLQ 알람 발화 감지 (elapsed=${elapsed}s)"
            return 0
        fi
        echo -n "."
        sleep "${POLL_INTERVAL_S}"
        elapsed=$((elapsed + POLL_INTERVAL_S))
    done

    echo ""
    return 1
}

# promtool test rules 실행 (docker 경유) — DLQ 픽스처
run_promtool_dlq() {
    print_section "▶ promtool test rules — DLQ 픽스처 (docker 경유)"
    print_warning "  이미지: ${PROM_IMAGE}"
    print_warning "  픽스처: observability/prometheus/rules/tests/dlq_test.yml"
    echo ""

    if ! docker info > /dev/null 2>&1; then
        print_error "[FAIL] Docker 미기동 — promtool 실행 불가"
        return 1
    fi

    docker run --rm --entrypoint /bin/promtool \
        -v "${ROOT_DIR}/observability/prometheus:/work" \
        "${PROM_IMAGE}" \
        test rules /work/rules/tests/dlq_test.yml
}

# 통합테스트 위임 안내 출력
show_integration_test_guidance() {
    print_section "▶ DLQ 라이브 발화 대체 — 통합테스트 위임 안내"
    echo ""

    print_section "  [EOS 경로] payment-service EOS commit 실패 → DLQ 도달"
    print_warning "  검증 클래스: PaymentEosIntegrationTest"
    print_warning "  위치: payment-service/src/test/java/.../"
    print_warning "  확인 포인트:"
    print_warning "    - EOS commit 반복 실패 시 AfterRollbackProcessor 경유 DLQ 도달"
    print_warning "    - payment_eos_commit_failure_dlq_total 카운터 증가"
    print_warning "    - DlqAppCounterRising 발화 조건 충족 확인"
    echo ""

    print_section "  [pg 경로] pg-service retry 소진 → QUARANTINED"
    print_warning "  검증 클래스: PgSelfLoopRetryExhaustionIntegrationTest"
    print_warning "  위치: pg-service/src/test/java/.../"
    print_warning "  확인 포인트:"
    print_warning "    - pg retry attempt≥4 소진 시 QUARANTINED 상태 전이"
    print_warning "    - pg_retry_exhausted_quarantine_total 카운터 증가"
    print_warning "    - DlqAppCounterRising 발화 조건 충족 확인"
    echo ""

    print_section "  [통합테스트 실행]"
    print_warning "  EOS:  ./gradlew :payment-service:test --tests \"*PaymentEosIntegrationTest\""
    print_warning "  pg:   ./gradlew :pg-service:test --tests \"*PgSelfLoopRetryExhaustionIntegrationTest\""
    echo ""

    print_section "  [라이브 발화 격하 사유]"
    print_warning "  EOS 경로: latency 주입 지연이 transaction.timeout.ms 미만이면"
    print_warning "            EOS commit timeout 이 발화하지 않아 DLQ 미도달"
    print_warning "            (실측: latency 2000ms 주입, abort 미발화)"
    print_warning "  pg 경로:  벤더 HTTP toxic 이 드릴 compose 구성에 미포함."
    print_warning "            실 벤더 sandbox 인증(secret 설정) 전제 — 기본 환경 불가"
    print_warning "            (FakePgGatewayStrategy 는 실 벤더 호출 우회)"
}

# ── 라이브 폴링 경로 (--live 모드) ────────────────────────────────────────
try_live_dlq_polling() {
    print_section "▶ DLQ 알람 라이브 폴링 (--live 모드)"
    print_warning "  DLQ 라이브 주입 없이 현재 환경의 알람 상태만 폴링한다."
    print_warning "  실제 DLQ 발화를 유발하는 주입(EOS commit 실패 / pg toxic)은 수동 선행 필요."
    echo ""

    if ! check_prometheus_available; then
        print_warning "[SKIP] Prometheus API 응답 없음 — observability 스택 기동 여부 확인"
        print_warning "  docker compose ... -f docker/docker-compose.observability.yml up -d"
        return 1
    fi

    print_info "[OK] Prometheus API 응답 확인 (${PROM_URL})"
    echo ""

    if poll_dlq_alerts; then
        print_info "✅ 라이브 DLQ 알람 발화 감지"
        return 0
    else
        print_warning "[TIMEOUT] DLQ 알람 미발화 (${POLL_TIMEOUT_S}s 경과)"
        print_warning "  라이브 주입 없이 폴링만으로는 발화 불가 — 격하 폴백으로 전환"
        return 1
    fi
}

# ── 메인 ─────────────────────────────────────────────────────────────────────
print_section "════════════════════════════════════════════════════════════"
print_section "  alert-firing-dlq — DLQ 적체 알람 발화 검증"
print_section "════════════════════════════════════════════════════════════"
echo ""

LIVE_PASSED=false

if [ "${LIVE_MODE}" = "true" ]; then
    print_warning "[--live 모드] 라이브 폴링 활성화 — DLQ 알람 상태 폴링 후 격하 폴백"
    echo ""
    if try_live_dlq_polling; then
        LIVE_PASSED=true
    fi
fi

if [ "${LIVE_PASSED}" = "true" ]; then
    exit 0
fi

# ── 격하 폴백: promtool test rules + 통합테스트 위임 ─────────────────────
print_section "════════════════════════════════════════════════════════════"
print_section "  격하 폴백 — promtool test rules (DLQ 픽스처)"
print_section "════════════════════════════════════════════════════════════"
print_warning "  EOS 경로 + pg 경로 모두 라이브 결정적 발화 불가로 격하 폴백 실행."
print_warning "  규칙은 Prometheus 라이브 로드 + 운영 유효."
print_warning "  promtool 합성 시계열 6케이스:"
print_warning "    (a) 앱 카운터 increase>0 → DlqAppCounterRising FIRING"
print_warning "    (b) .dlq offset increase>0 → DlqTopicOffsetRising FIRING"
print_warning "    (c) 정상(델타 0) → 3개 알람 모두 미발화"
print_warning "    (d) 앱 카운터만↑(offset 없음) → DlqAppCounterRising만 FIRING (독립 회귀 고정)"
print_warning "    (e) offset만↑(앱 카운터 없음) → DlqTopicOffsetRising만 FIRING (독립 회귀 고정)"
print_warning "    (f) commands.confirm.dlq 컨슈머 lag 잔존 → DlqCommandsConsumerLag FIRING"
echo ""

PROMTOOL_PASSED=false
if run_promtool_dlq; then
    PROMTOOL_PASSED=true
fi

echo ""
show_integration_test_guidance

echo ""
if [ "${PROMTOOL_PASSED}" = "true" ]; then
    print_info "✅ 격하 폴백 PASS — promtool test rules 6케이스 통과"
    print_info "   규칙 발화 로직 검증 완료. 라이브 발화는 환경 한계로 통합테스트 위임."
    exit 0
else
    print_error "❌ 격하 폴백 FAIL — promtool test rules 실패"
    print_error "   규칙 파일 또는 픽스처 오류 확인:"
    print_error "     observability/prometheus/rules/dlq.yml"
    print_error "     observability/prometheus/rules/tests/dlq_test.yml"
    exit 1
fi
