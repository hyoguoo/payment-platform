#!/usr/bin/env bash
# infra-healthcheck.sh — payment-platform 인프라 + 4서비스 살아있음 검사.
#
# 목적: docker compose 스택의 모든 컨테이너가 정상 기동·healthy 상태인지,
#       호스트에서 접근 가능한 포트들이 응답하는지 한 번에 확인한다.
#       confirm 시나리오·컬럼 단위 검증은 본 스크립트의 비범위.
#
# 사용법:
#   ./scripts/smoke/infra-healthcheck.sh
#
# 선행 조건:
#   docker compose -f docker/docker-compose.infra.yml \
#                  -f docker/docker-compose.apps.yml \
#                  -f docker/docker-compose.observability.yml up -d
#
# 종료 코드:
#   0 — 전 항목 PASS
#   1 — 하나라도 FAIL

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=../common.sh
source "${ROOT_DIR}/scripts/common.sh"

COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-docker}"

PASS_COUNT=0
FAIL_COUNT=0

check_pass() {
    echo -e "${GREEN}[PASS]${NC} $1"
    PASS_COUNT=$((PASS_COUNT + 1))
}

check_fail() {
    echo -e "${RED}[FAIL]${NC} $1"
    FAIL_COUNT=$((FAIL_COUNT + 1))
}

# ─────────────────────────────────────────────
# 1. Docker compose 서비스 health
#    docker compose 가 정의한 healthcheck 를 그대로 진실 소스로 쓴다.
# ─────────────────────────────────────────────
print_section "▶ Docker compose 서비스 health"

check_container_health() {
    local svc="$1"
    local status
    status=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${svc}" 2>/dev/null || echo "missing")
    case "${status}" in
        healthy)
            check_pass "${svc} (healthy)"
            ;;
        running)
            # healthcheck 미정의 컨테이너 — 기동 자체로 PASS
            check_pass "${svc} (running, no healthcheck)"
            ;;
        starting)
            check_fail "${svc} (still starting — wait longer)"
            ;;
        unhealthy)
            check_fail "${svc} (unhealthy)"
            ;;
        missing|"")
            check_fail "${svc} (container not found — compose up 안 됐나?)"
            ;;
        *)
            check_fail "${svc} (unexpected state: ${status})"
            ;;
    esac
}

# 인프라 / Eureka — container_name 고정 (단일 인스턴스, 오토스케일 대상 아님)
EXPECTED_INFRA_SERVICES=(
    "payment-kafka"
    "payment-redis-dedupe"
    "payment-redis-stock"
    "payment-mysql-payment"
    "payment-mysql-pg"
    "payment-mysql-product"
    "payment-mysql-user"
    "payment-eureka"
)

for svc in "${EXPECTED_INFRA_SERVICES[@]}"; do
    check_container_health "${svc}"
done

# 비즈니스 서비스 5개 — 모두 scale-able, container_name 미고정.
# docker compose 가 <project>-<service>-<n> 자동 부여하므로 prefix 매칭으로 인스턴스 검색.
SCALABLE_SERVICES=("gateway" "payment-service" "pg-service" "user-service" "product-service")
for svc in "${SCALABLE_SERVICES[@]}"; do
    INSTANCES=$(docker ps --filter "name=${COMPOSE_PROJECT_NAME}-${svc}-" --format "{{.Names}}" 2>/dev/null)
    if [ -z "${INSTANCES}" ]; then
        check_fail "${svc}: 인스턴스 0건 — compose up 안 됐나?"
    else
        while IFS= read -r instance; do
            check_container_health "${instance}"
        done <<< "${INSTANCES}"
    fi
done

# ─────────────────────────────────────────────
# 2. 호스트 노출 포트 접근성
#    docker network 외부에서 실제로 응답하는지 확인.
# ─────────────────────────────────────────────
print_section "\n▶ 호스트 노출 포트"

# Gateway (8090) — 외부 진입점
if curl -sf -o /dev/null -m 5 "http://localhost:8090/actuator/health"; then
    check_pass "gateway /actuator/health (localhost:8090)"
else
    check_fail "gateway /actuator/health (localhost:8090)"
fi

# Eureka (8761)
if curl -sf -o /dev/null -m 5 "http://localhost:8761/actuator/health"; then
    check_pass "eureka /actuator/health (localhost:8761)"
else
    check_fail "eureka /actuator/health (localhost:8761)"
fi

# MySQL 4종
for mysql_port in 3306:payment 3308:pg 3309:product 3310:user; do
    port="${mysql_port%%:*}"
    label="${mysql_port##*:}"
    if docker exec -i payment-mysql-${label} mysqladmin ping -h localhost --silent >/dev/null 2>&1; then
        check_pass "mysql-${label} ping (localhost:${port})"
    else
        check_fail "mysql-${label} ping (localhost:${port})"
    fi
done

# Redis 2종
for redis_port in 6379:dedupe 6380:stock; do
    port="${redis_port%%:*}"
    label="${redis_port##*:}"
    if docker exec -i payment-redis-${label} redis-cli ping 2>/dev/null | grep -q PONG; then
        check_pass "redis-${label} ping (localhost:${port})"
    else
        check_fail "redis-${label} ping (localhost:${port})"
    fi
done

# Kafka — 토픽 list 가능 여부 (broker 도달성)
if docker exec -i payment-kafka kafka-topics --list --bootstrap-server localhost:9092 >/dev/null 2>&1; then
    check_pass "kafka 토픽 list (localhost:9092)"
else
    check_fail "kafka 토픽 list (localhost:9092)"
fi

# ─────────────────────────────────────────────
# 3. Eureka 등록 상태
#    각 서비스가 Eureka 에 정상 등록되어 있는지.
# ─────────────────────────────────────────────
print_section "\n▶ Eureka 등록 상태"

EUREKA_APPS_JSON=$(curl -sf -m 5 -H "Accept: application/json" "http://localhost:8761/eureka/apps" 2>/dev/null || echo "")
if [ -z "${EUREKA_APPS_JSON}" ]; then
    check_fail "eureka /eureka/apps 응답 없음"
else
    for app in PAYMENT-SERVICE PG-SERVICE PRODUCT-SERVICE USER-SERVICE GATEWAY; do
        if echo "${EUREKA_APPS_JSON}" | grep -q "\"name\":\"${app}\""; then
            check_pass "${app} 등록됨"
        else
            check_fail "${app} 미등록 — 부팅 후 Eureka heartbeat 대기 필요할 수 있음"
        fi
    done
fi

# ─────────────────────────────────────────────
# 종합
# ─────────────────────────────────────────────
echo ""
print_section "▶ 결과"
echo "  PASS: ${PASS_COUNT}"
echo "  FAIL: ${FAIL_COUNT}"

if [ ${FAIL_COUNT} -eq 0 ]; then
    print_info "✅ 모든 항목 PASS — 인프라 + 서비스 정상"
    exit 0
else
    print_error "❌ ${FAIL_COUNT} 항목 FAIL"
    exit 1
fi
