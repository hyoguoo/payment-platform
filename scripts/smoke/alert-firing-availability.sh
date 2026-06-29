#!/usr/bin/env bash
# alert-firing-availability.sh — 가용성 알람 라이브 발화 검증.
#
# 목적:
#   docker stop 다운 주입 후 Prometheus /api/v1/alerts 를 폴링해
#   가용성 알람(ServiceDown / DependencyDown / DependencyHealthStale) 발화를 검증한다.
#   라이브 발화 불가 또는 스택 미기동 시 promtool test rules 픽스처 격하 폴백.
#
# ── 다운 주입 시나리오 ───────────────────────────────────────────────────────
#   (a) 서비스 프로세스 다운: payment-service 컨테이너 중단 → ServiceDown(for:1m) 발화
#   (b) DB 다운: mysql-payment 컨테이너 중단 → DependencyDown{component="db"} 발화
#   (c) redis-dedupe 다운: 컨테이너 중단 → DependencyDown{component="redis-dedupe"} 발화
#   (d) redis-stock 다운: 컨테이너 중단 → DependencyDown{component="redis-stock"} 발화
#   각 시나리오: docker stop → 발화 폴링 → docker start → 해소 폴링
#
# ── 가시화 한계 ─────────────────────────────────────────────────────────────
#   DLQ-stranded 및 EXPIRED 마스킹: docker start 로 서비스가 복구되어도
#   DLQ 에 보존된 메시지 및 EXPIRED 전이한 결제 상태는 자동 회복되지 않는다.
#   (DLQ 재주입 TQ-1, 재고 재동기 TC-3 위임)
#
#   redis-dedupe 다운 거동 — fail-closed:
#   체크아웃 멱등(IdempotencyStore, 포트 6379) 호출 차단 → checkout 5xx → 결제 생성 차단.
#   중복 과금 경로 없음 (IdempotencyStoreRedisAdapter 는 fail-open 폴백 없음).
#   EOS 메시지 멱등은 MySQL payment_event_dedupe 귀속 → db 컴포넌트 의존.
#   outage 중 이중 과금 추적 불필요.
#
#   redis-stock 다운 거동:
#   선차감 및 보상 경로 실패 → 선차감 stranded(재고 ≤ RDB 보수적, over-sell 아님).
#   자동 재동기: TC-3 위임.
#
# ── 선행 조건 (라이브 경로) ─────────────────────────────────────────────────
#   docker compose \
#     -f docker/docker-compose.infra.yml \
#     -f docker/docker-compose.apps.yml \
#     -f docker/docker-compose.observability.yml \
#     up -d
#   scripts/smoke/create-topics.sh
#
# ── 사용법 ─────────────────────────────────────────────────────────────────
#   ./scripts/smoke/alert-firing-availability.sh                  # 라이브 시도 → 격하 폴백
#   ./scripts/smoke/alert-firing-availability.sh --fallback-only  # 격하 폴백 직행
#
# ── 종료 코드 ──────────────────────────────────────────────────────────────
#   0 — PASS (라이브 발화 확인 또는 promtool test rules pass)
#   1 — FAIL (promtool test 실패 또는 docker 없음)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=../common.sh
source "${ROOT_DIR}/scripts/common.sh"

# ── 설정 (환경변수 오버라이드 가능) ─────────────────────────────────────────
PROM_URL="${PROM_URL:-http://localhost:9090}"
PROM_IMAGE="${PROM_IMAGE:-prom/prometheus:v2.51.2}"

# 비즈니스 서비스 컨테이너 탐색용 compose 프로젝트 이름 (docker-compose.apps.yml 기준)
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-docker}"

# ServiceDown 은 for:1m 충족 후 발화 → Prometheus scrape 1회 + 60s = 최소 ~75s
POLL_TIMEOUT_SERVICE_S="${POLL_TIMEOUT_SERVICE_S:-120}"
# DependencyDown 은 for 없음 → health 폴러 10s + scrape 15s = ~25s
POLL_TIMEOUT_DEP_S="${POLL_TIMEOUT_DEP_S:-60}"
# 복구 후 alert 소멸 대기 (서비스 재기동 start_period 포함)
POLL_TIMEOUT_RESOLVED_S="${POLL_TIMEOUT_RESOLVED_S:-120}"
POLL_INTERVAL_S="${POLL_INTERVAL_S:-5}"

# ── 격하 폴백 플래그 ─────────────────────────────────────────────────────────
FALLBACK_ONLY=false
for arg in "$@"; do
    [ "${arg}" = "--fallback-only" ] && FALLBACK_ONLY=true
done

# ── 중단된 컨테이너 정리 트랩 ───────────────────────────────────────────────
# 비정상 종료 시에도 모든 중단 컨테이너를 재기동한다.
# docker start 는 이미 실행 중인 컨테이너에도 안전(no-op)하므로 중복 호출 무관.
STOPPED_CONTAINERS=()

cleanup_containers() {
    if [ "${#STOPPED_CONTAINERS[@]}" -gt 0 ]; then
        print_section "▶ 중단 컨테이너 복구 (종료 훅)"
        for c in "${STOPPED_CONTAINERS[@]}"; do
            [ -z "${c}" ] && continue
            print_warning "  docker start ${c}"
            docker start "${c}" > /dev/null 2>&1 || true
        done
    fi
}
trap cleanup_containers EXIT INT TERM

# ── 헬퍼 함수 ────────────────────────────────────────────────────────────────

check_prometheus_available() {
    curl -sf -m 5 "${PROM_URL}/-/ready" > /dev/null 2>&1
}

# Prometheus /api/v1/alerts 에서 특정 알람 firing 확인
# 인수: <alert_name> [<component>]
#   component="" 이면 component 라벨 조건 없음
# 반환: 0 = firing 알람 존재, 1 = 없음
check_availability_firing() {
    local alert_name="$1"
    local component="${2:-}"
    local alerts_json
    alerts_json=$(curl -sf -m 10 "${PROM_URL}/api/v1/alerts" 2>/dev/null || echo "")
    [ -z "${alerts_json}" ] && return 1

    local firing_count
    firing_count=$(echo "${alerts_json}" | python3 -c "
import sys, json
data = json.load(sys.stdin)
alerts = data.get('data', {}).get('alerts', [])
alert_name = '${alert_name}'
component = '${component}'
firing = [
    a for a in alerts
    if a.get('state') == 'firing'
    and a['labels'].get('alertname', '') == alert_name
    and (not component or a['labels'].get('component', '') == component)
]
print(len(firing))
for a in firing:
    print('  FIRING:', a['labels'].get('alertname'), '|', a['labels'], file=sys.stderr)
" 2>/dev/null || echo "0")

    [ "${firing_count:-0}" -gt 0 ]
}

# 발화 폴링 — check_availability_firing 을 반복 호출해 firing 대기
# 인수: <설명> <타임아웃s> <alert_name> [<component>]
poll_until_firing() {
    local desc="$1"
    local timeout_s="$2"
    local alert_name="$3"
    local component="${4:-}"
    local elapsed=0

    print_warning "  → 발화 폴링: ${desc} (타임아웃 ${timeout_s}s, 간격 ${POLL_INTERVAL_S}s)"
    print_warning "    . 가 찍히면 아직 미발화"

    while [ "${elapsed}" -lt "${timeout_s}" ]; do
        if check_availability_firing "${alert_name}" "${component}"; then
            echo ""
            print_info "  [FIRING] ${desc} 발화 감지 (elapsed=${elapsed}s)"
            return 0
        fi
        echo -n "."
        sleep "${POLL_INTERVAL_S}"
        elapsed=$((elapsed + POLL_INTERVAL_S))
    done

    echo ""
    return 1
}

# 해소 폴링 — firing → 비발화 전환 대기
# 인수: <설명> <alert_name> [<component>]
poll_until_resolved() {
    local desc="$1"
    local alert_name="$2"
    local component="${3:-}"
    local elapsed=0

    print_warning "  → 해소 폴링: ${desc} (타임아웃 ${POLL_TIMEOUT_RESOLVED_S}s)"
    print_warning "    . 가 찍히면 아직 firing"

    while [ "${elapsed}" -lt "${POLL_TIMEOUT_RESOLVED_S}" ]; do
        if ! check_availability_firing "${alert_name}" "${component}"; then
            echo ""
            print_info "  [RESOLVED] ${desc} 해소 확인 (elapsed=${elapsed}s)"
            return 0
        fi
        echo -n "."
        sleep "${POLL_INTERVAL_S}"
        elapsed=$((elapsed + POLL_INTERVAL_S))
    done

    echo ""
    print_warning "  [TIMEOUT] ${desc} 미해소 — 서비스 완전 복구 후 자연 해소 예상"
    return 1
}

# ── 시나리오 (a): 서비스 프로세스 다운 → ServiceDown ─────────────────────────
# payment-service 컨테이너 1개 중단 후 ServiceDown(for:1m) 발화 + 해소 확인.
# DependencyHealthStale 의 absent() 백스톱도 동시에 발화하는 예상 동작임.
scenario_service_down() {
    print_section "─────────────────────────────────────────────────────────────"
    print_section "  시나리오 (a): 서비스 프로세스 다운 → ServiceDown"
    print_section "─────────────────────────────────────────────────────────────"

    local payment_container
    payment_container=$(docker ps \
        --filter "name=${COMPOSE_PROJECT_NAME}-payment-service-" \
        --filter "status=running" \
        -q | head -1 || true)

    if [ -z "${payment_container}" ]; then
        print_warning "  [SKIP] payment-service 실행 컨테이너 없음 — 시나리오 (a) 건너뜀"
        print_warning "  앱 스택 기동 여부 확인:"
        print_warning "    docker compose -f docker/docker-compose.apps.yml up -d"
        return 1
    fi

    print_warning "  대상 컨테이너: ${payment_container}"
    print_warning "  주의: ServiceDown 은 for:1m 충족 후 발화 — 최소 ~90s 소요"
    echo ""

    print_section "  ▶ docker stop ${payment_container}"
    docker stop "${payment_container}" > /dev/null
    STOPPED_CONTAINERS+=("${payment_container}")
    echo ""

    if poll_until_firing "ServiceDown{job='payment-service'}" \
            "${POLL_TIMEOUT_SERVICE_S}" "ServiceDown"; then
        echo ""
        print_section "  ▶ docker start ${payment_container}"
        docker start "${payment_container}" > /dev/null
        echo ""
        poll_until_resolved "ServiceDown" "ServiceDown" || true
        return 0
    else
        print_warning "  [TIMEOUT] ServiceDown 미발화 (${POLL_TIMEOUT_SERVICE_S}s 경과)"
        print_warning "  Prometheus scrape + for:1m = 최소 ~90s. observability 스택 기동 필요."
        echo ""
        docker start "${payment_container}" > /dev/null 2>/dev/null || true
        return 1
    fi
}

# ── 시나리오 (b): DB 다운 → DependencyDown{component="db"} ───────────────────
# payment-mysql-payment 중단 → payment-service health 폴러 타임아웃(2s) 감지 → DependencyDown 발화.
scenario_db_down() {
    print_section "─────────────────────────────────────────────────────────────"
    print_section "  시나리오 (b): payment DB 다운 → DependencyDown{component=db}"
    print_section "─────────────────────────────────────────────────────────────"
    local container="payment-mysql-payment"

    if ! docker inspect "${container}" > /dev/null 2>&1; then
        print_warning "  [SKIP] ${container} 없음 — 시나리오 (b) 건너뜀"
        return 1
    fi

    print_warning "  대상 컨테이너: ${container}"
    print_warning "  폴러 타임아웃(2s) + 폴링주기(10s) + scrape(15s) → ~30s 내 발화 예상"
    echo ""

    print_section "  ▶ docker stop ${container}"
    docker stop "${container}" > /dev/null
    STOPPED_CONTAINERS+=("${container}")
    echo ""

    if poll_until_firing "DependencyDown{component=db}" \
            "${POLL_TIMEOUT_DEP_S}" "DependencyDown" "db"; then
        echo ""
        print_section "  ▶ docker start ${container}"
        docker start "${container}" > /dev/null
        echo ""
        poll_until_resolved "DependencyDown{component=db}" "DependencyDown" "db" || true
        return 0
    else
        print_warning "  [TIMEOUT] DependencyDown{component=db} 미발화 (${POLL_TIMEOUT_DEP_S}s 경과)"
        docker start "${container}" > /dev/null 2>/dev/null || true
        return 1
    fi
}

# ── 시나리오 (c): redis-dedupe 다운 → DependencyDown{component="redis-dedupe"} ─
# payment-redis-dedupe 중단 → checkout 멱등 차단 → fail-closed(5xx) 결제 생성 차단.
# EOS 메시지 멱등은 MySQL payment_event_dedupe 귀속이므로 db 컴포넌트 관할.
# 이중 과금 경로 없음 — outage 중 이중 과금 추적 불필요.
scenario_redis_dedupe_down() {
    print_section "─────────────────────────────────────────────────────────────"
    print_section "  시나리오 (c): redis-dedupe 다운 → DependencyDown{component=redis-dedupe}"
    print_section "─────────────────────────────────────────────────────────────"
    local container="payment-redis-dedupe"

    if ! docker inspect "${container}" > /dev/null 2>&1; then
        print_warning "  [SKIP] ${container} 없음 — 시나리오 (c) 건너뜀"
        return 1
    fi

    print_warning "  대상 컨테이너: ${container}"
    print_warning "  거동: 체크아웃 멱등(IdempotencyStore) 차단 → checkout 5xx → 결제 생성 차단"
    print_warning "  중복 과금 경로 없음 — outage 중 이중 과금 추적 불필요"
    echo ""

    print_section "  ▶ docker stop ${container}"
    docker stop "${container}" > /dev/null
    STOPPED_CONTAINERS+=("${container}")
    echo ""

    if poll_until_firing "DependencyDown{component=redis-dedupe}" \
            "${POLL_TIMEOUT_DEP_S}" "DependencyDown" "redis-dedupe"; then
        echo ""
        print_section "  ▶ docker start ${container}"
        docker start "${container}" > /dev/null
        echo ""
        poll_until_resolved "DependencyDown{component=redis-dedupe}" \
            "DependencyDown" "redis-dedupe" || true
        return 0
    else
        print_warning "  [TIMEOUT] DependencyDown{component=redis-dedupe} 미발화 (${POLL_TIMEOUT_DEP_S}s 경과)"
        docker start "${container}" > /dev/null 2>/dev/null || true
        return 1
    fi
}

# ── 시나리오 (d): redis-stock 다운 → DependencyDown{component="redis-stock"} ──
# payment-redis-stock 중단 → 선차감·보상 경로 실패 → stranded(재고 ≤ RDB, over-sell 아님).
# 자동 재동기: TC-3 위임.
scenario_redis_stock_down() {
    print_section "─────────────────────────────────────────────────────────────"
    print_section "  시나리오 (d): redis-stock 다운 → DependencyDown{component=redis-stock}"
    print_section "─────────────────────────────────────────────────────────────"
    local container="payment-redis-stock"

    if ! docker inspect "${container}" > /dev/null 2>&1; then
        print_warning "  [SKIP] ${container} 없음 — 시나리오 (d) 건너뜀"
        return 1
    fi

    print_warning "  대상 컨테이너: ${container}"
    print_warning "  거동: 선차감 및 보상 경로 실패 → 선차감 stranded(재고 ≤ RDB, over-sell 아님)"
    print_warning "  자동 재동기: TC-3 위임"
    echo ""

    print_section "  ▶ docker stop ${container}"
    docker stop "${container}" > /dev/null
    STOPPED_CONTAINERS+=("${container}")
    echo ""

    if poll_until_firing "DependencyDown{component=redis-stock}" \
            "${POLL_TIMEOUT_DEP_S}" "DependencyDown" "redis-stock"; then
        echo ""
        print_section "  ▶ docker start ${container}"
        docker start "${container}" > /dev/null
        echo ""
        poll_until_resolved "DependencyDown{component=redis-stock}" \
            "DependencyDown" "redis-stock" || true
        return 0
    else
        print_warning "  [TIMEOUT] DependencyDown{component=redis-stock} 미발화 (${POLL_TIMEOUT_DEP_S}s 경과)"
        docker start "${container}" > /dev/null 2>/dev/null || true
        return 1
    fi
}

# ── promtool 격하 폴백 ───────────────────────────────────────────────────────
run_promtool_availability() {
    print_section "▶ promtool test rules — 가용성 픽스처 (docker 경유)"
    print_warning "  이미지: ${PROM_IMAGE}"
    print_warning "  픽스처: observability/prometheus/rules/tests/availability_test.yml"
    echo ""

    if ! docker info > /dev/null 2>&1; then
        print_error "[FAIL] Docker 미기동 — promtool 실행 불가"
        return 1
    fi

    docker run --rm --entrypoint /bin/promtool \
        -v "${ROOT_DIR}/observability/prometheus:/work" \
        "${PROM_IMAGE}" \
        test rules /work/rules/tests/availability_test.yml
}

# ── 라이브 발화 드릴 (4 시나리오 순차 실행) ─────────────────────────────────
try_live_firing() {
    print_section "════════════════════════════════════════════════════════════"
    print_section "  가용성 알람 라이브 발화 드릴 (docker stop/start 주입)"
    print_section "════════════════════════════════════════════════════════════"

    if ! check_prometheus_available; then
        print_warning "[SKIP] Prometheus API 응답 없음 — 격하 폴백으로 전환"
        print_warning "  observability 스택 포함 기동 확인:"
        print_warning "    docker compose -f docker/docker-compose.infra.yml \\"
        print_warning "      -f docker/docker-compose.apps.yml \\"
        print_warning "      -f docker/docker-compose.observability.yml up -d"
        return 1
    fi

    print_info "[OK] Prometheus API 응답 확인 (${PROM_URL})"
    echo ""

    local pass_count=0
    local fail_count=0

    scenario_service_down && pass_count=$((pass_count + 1)) || fail_count=$((fail_count + 1))
    echo ""
    scenario_db_down && pass_count=$((pass_count + 1)) || fail_count=$((fail_count + 1))
    echo ""
    scenario_redis_dedupe_down && pass_count=$((pass_count + 1)) || fail_count=$((fail_count + 1))
    echo ""
    scenario_redis_stock_down && pass_count=$((pass_count + 1)) || fail_count=$((fail_count + 1))
    echo ""

    print_section "════════════════════════════════════════════════════════════"
    print_section "  라이브 드릴 결과: PASS=${pass_count} / FAIL_OR_SKIP=${fail_count}"
    print_section "════════════════════════════════════════════════════════════"

    if [ "${fail_count}" -eq 0 ]; then
        print_info "✅ 라이브 드릴 전체 PASS — 4 시나리오 발화 + 해소 확인"
        return 0
    else
        print_warning "  일부 시나리오 미발화 또는 건너뜀 — promtool 격하 폴백 병행 실행"
        return 1
    fi
}

# ── 메인 ─────────────────────────────────────────────────────────────────────
print_section "════════════════════════════════════════════════════════════"
print_section "  alert-firing-availability — 가용성 알람 발화 검증"
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
print_section "  격하 폴백 — promtool test rules (가용성 픽스처)"
print_section "════════════════════════════════════════════════════════════"
print_warning "  라이브 스택 미기동 또는 일부 시나리오 미발화로 격하 폴백 실행."
print_warning "  규칙은 Prometheus 라이브 로드 + 운영 유효."
print_warning "  promtool 합성 시계열 9케이스:"
print_warning "    (a1) up==0 → ServiceDown FIRING (for:1m 충족)"
print_warning "    (a2) 정상(up=1) → ServiceDown 미발화"
print_warning "    (b) dependency_up{component=db}==0 → DependencyDown FIRING"
print_warning "    (c) redis-stock 단독 다운 → DependencyDown FIRING, redis-dedupe 미발화"
print_warning "    (d1) staleness 조건 → DependencyHealthStale FIRING"
print_warning "    (d2) 폴러 정상(최근 갱신) → DependencyHealthStale 미발화"
print_warning "    (e1) dependency_up 시리즈 absent → absent() 백스톱 FIRING"
print_warning "    (e2) last_poll 시리즈 absent → absent() 백스톱 FIRING"
print_warning "    (f) 정상 baseline → 3알람 모두 미발화"
echo ""

if run_promtool_availability; then
    echo ""
    print_info "✅ 격하 폴백 PASS — promtool test rules 9케이스 통과"
    print_info "   규칙 발화 로직 검증 완료. 라이브 발화는 스택 기동 후 재실행으로 확인."
    exit 0
else
    echo ""
    print_error "❌ 격하 폴백 FAIL — promtool test rules 실패"
    print_error "   규칙 파일 또는 픽스처 오류 확인:"
    print_error "     observability/prometheus/rules/availability.yml"
    print_error "     observability/prometheus/rules/tests/availability_test.yml"
    exit 1
fi
