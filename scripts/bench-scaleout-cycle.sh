#!/usr/bin/env bash
# bench-scaleout-cycle.sh — 사이클 하나(조건 값 조합)를 인자만 주고 끝까지 무인 실행한다.
#
# 순서: 스택 기동(관측 스택 포함) → 클러스터 구성 → 시드 → 부하 → 종결 대기 → 정합 검증
#       → (통과 시에만) 재구성
#
# 정합 판정은 scripts/k6/verify-settlement.sh 의 종료 코드로 받는다:
#   0(PASS)         — 처리율/지연/복제 지연을 결과 파일에 남기고 bench-cycle-reset.sh 로 재구성
#   2(INCONCLUSIVE) — INCONCLUSIVE_RETRY_WAIT_SECONDS 만큼 기다렸다 최대 INCONCLUSIVE_MAX_RETRIES
#                     회까지 재검증. 그래도 보류면 사이클 실패 — 재구성을 부르지 않는다
#   3(MISMATCH)     — 즉시 사이클 실패. 재시도도 재구성도 하지 않는다(캐시를 비우면 어긋난
#                     값과 선차감 기록이 지워져 원인을 되짚을 수 없다)
#   1 또는 그 외     — 접속·전제 실패나 이 러너가 모르는 코드. 통과로 읽지 않고 실패로 다룬다
#
# 실패로 멈춘 사이클의 잔류는 이 스크립트가 치우지 않는다 — 사람이 scripts/bench-cycle-reset.sh
# 를 따로 돌린다. 격리 종결처럼 사람 판단이 필요한 자리가 있어 러너가 알아서 밀고 가면 안 된다.
#
# 사용법:
#   INSTANCES=2 STOCK_MASTERS=4 bash scripts/bench-scaleout-cycle.sh
#   # 짧은 흐름 확인(smoke) — PEAK_RATE/STAGE_SEC 로 부하를 짧게 줄인다:
#   INSTANCES=1 STOCK_MASTERS=1 K6_EXTRA_ARGS="-e PEAK_RATE=10 -e STAGE_SEC=5" \
#     bash scripts/bench-scaleout-cycle.sh
#
# 조건 값 (환경 변수):
#   INSTANCES        — payment-service 인스턴스 수, 1/2/3/4 (기본 1)
#   STOCK_MASTERS     — 재고 캐시 클러스터 마스터 수, 1/2/4 (기본 2)
#   DEDUPE_MASTERS    — 멱등 저장소 클러스터 마스터 수 — 설계상 변수 아님, 고정 3 (기본 3)
#   POLLING_ROUTE     — 폴링 조회를 복제본으로 보낼지, on/off (기본 on)
#   ITEMS_PER_ORDER   — 주문당 상품 수 (기본 1, 다중 상품 축에서만 3)
#   VENDOR_LATENCY    — pg fake gateway 지연, low/high (기본 low. low=100~300ms, high=800~1500ms)
#   CASE_NAME         — 결과 파일명(미지정 시 조건 값으로 자동 합성)
#
# 측정 튜닝 (환경 변수, 대개 기본값 그대로 둔다):
#   BASE_URL                  — k6 요청 기저 URL (기본 http://localhost:8090 — gateway 경유)
#   RECONCILER_TIMEOUT        — payment reconciler IN_PROGRESS 회수 기준 초 (기본 300 — 코드
#                               default 와 동일. 커넥션 풀 80/피크 400 조합이 포화에 닿을 때
#                               확정 지연 꼬리가 벌어질 수 있어, 회수가 아직 진행 중인 확정
#                               결과를 앞지르지 않도록 짧게 단축하지 않는다)
#   RECONCILER_SCAN_MS        — payment reconciler 스캔 주기 ms (기본 15000)
#   HIKARI_MAX_POOL           — payment Hikari DB 커넥션 풀 상한 (기본 80 — 부하 곡선 피크
#                               400 req/s 에 맞춘 값. docker-compose.benchmark.yml 이 이 값을
#                               SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE 로 그대로 받는다)
#   FAKE_FAIL_RATE            — pg fake gateway 실패율 (기본 0 — baseline 고정)
#   PRODUCT_COUNT / PRODUCT_ID_BASE / BENCH_STOCK — scripts/bench-seed-stock.sh 와 동일
#   K6_EXTRA_ARGS              — k6 run 에 추가 전달할 -e KEY=VALUE 인자(공백 구분)
#   REPLICA_SAMPLE_INTERVAL_SECONDS — 복제 지연 표본 주기 초 (기본 5)
#   INCONCLUSIVE_MAX_RETRIES        — 판단 보류 재검증 최대 횟수 (기본 3)
#   INCONCLUSIVE_RETRY_WAIT_SECONDS — 재검증 사이 대기 초 (기본 20)
#
# 결과 파일:
#   results/<CASE_NAME>-cycle.json — 조건 값, 처리율, 백분위 지연, 복제 지연, 정합 판정.
#   부하 도구가 쓰는 results/<CASE_NAME>.json 과 verify-settlement.sh 의
#   results/<CASE_NAME>-verdict.json 어느 쪽도 건드리지 않는다.
#
# 선행 조건:
#   - docker / k6 / jq 설치
#   - docker-compose.infra.yml 로 뜨는 인프라가 이미 기동 중이어야 하는 것은 아니다 —
#     이 스크립트가 필요한 서비스를 직접 올린다. 단 payment-infra-network 는 이미 있어야
#     한다(scripts/bench-redis-cluster.sh 의 선행 조건과 동일)
#
# 종료 코드:
#   0 — 사이클 성공(정합 PASS) — 결과 기록 + 재구성까지 완료
#   1 — 선결 조건 실패(도구 미설치, 스택 기동 실패, 시드 실패, k6 실행 오류 등) — 캐시 상태 불명,
#       사람이 직접 확인 필요
#   2 — 정합 판단 보류 소진(INCONCLUSIVE) — 캐시를 비우지 않고 실패
#   3 — 정합 불일치(MISMATCH) — 캐시를 비우지 않고 즉시 실패
#   4 — 정합 검증이 접속·전제 실패(exit 1) 또는 이 러너가 모르는 코드로 끝남 — 캐시를 비우지
#       않고 실패(통과로 읽지 않는다)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=common.sh
source "${ROOT_DIR}/scripts/common.sh"

# ---------------------------------------------------------------------------
# 조건 값
# ---------------------------------------------------------------------------

INSTANCES="${INSTANCES:-1}"
STOCK_MASTERS="${STOCK_MASTERS:-2}"
DEDUPE_MASTERS="${DEDUPE_MASTERS:-3}"
POLLING_ROUTE="${POLLING_ROUTE:-on}"
ITEMS_PER_ORDER="${ITEMS_PER_ORDER:-1}"
VENDOR_LATENCY="${VENDOR_LATENCY:-low}"

if ! [[ "${INSTANCES}" =~ ^[1-4]$ ]]; then
    print_error "❌ INSTANCES 는 1~4 여야 한다 (입력: '${INSTANCES}')"
    exit 1
fi
if ! [[ "${STOCK_MASTERS}" =~ ^[0-9]+$ ]] || [[ "${STOCK_MASTERS}" -lt 1 ]]; then
    print_error "❌ STOCK_MASTERS 는 1 이상의 정수여야 한다 (입력: '${STOCK_MASTERS}')"
    exit 1
fi
if [[ "${POLLING_ROUTE}" != "on" && "${POLLING_ROUTE}" != "off" ]]; then
    print_error "❌ POLLING_ROUTE 는 on 또는 off 여야 한다 (입력: '${POLLING_ROUTE}')"
    exit 1
fi
if [[ "${VENDOR_LATENCY}" != "low" && "${VENDOR_LATENCY}" != "high" ]]; then
    print_error "❌ VENDOR_LATENCY 는 low 또는 high 여야 한다 (입력: '${VENDOR_LATENCY}')"
    exit 1
fi

CASE_NAME="${CASE_NAME:-cycle-i${INSTANCES}-m${STOCK_MASTERS}-poll${POLLING_ROUTE}-items${ITEMS_PER_ORDER}-${VENDOR_LATENCY}}"

# ---------------------------------------------------------------------------
# 측정 튜닝
# ---------------------------------------------------------------------------

BASE_URL="${BASE_URL:-http://localhost:8090}"
RECONCILER_TIMEOUT="${RECONCILER_TIMEOUT:-300}"
RECONCILER_SCAN_MS="${RECONCILER_SCAN_MS:-15000}"
HIKARI_MAX_POOL="${HIKARI_MAX_POOL:-80}"
FAKE_FAIL_RATE="${FAKE_FAIL_RATE:-0}"
PRODUCT_COUNT="${PRODUCT_COUNT:-100}"
PRODUCT_ID_BASE="${PRODUCT_ID_BASE:-1000}"
BENCH_STOCK="${BENCH_STOCK:-10000000}"
K6_EXTRA_ARGS="${K6_EXTRA_ARGS:-}"
REPLICA_SAMPLE_INTERVAL_SECONDS="${REPLICA_SAMPLE_INTERVAL_SECONDS:-5}"
INCONCLUSIVE_MAX_RETRIES="${INCONCLUSIVE_MAX_RETRIES:-3}"
INCONCLUSIVE_RETRY_WAIT_SECONDS="${INCONCLUSIVE_RETRY_WAIT_SECONDS:-20}"

if [[ "${VENDOR_LATENCY}" == "low" ]]; then
    FAKE_LATENCY_MIN=100
    FAKE_LATENCY_MAX=300
else
    FAKE_LATENCY_MIN=800
    FAKE_LATENCY_MAX=1500
fi

RESULTS_DIR="${RESULTS_DIR:-${ROOT_DIR}/results}"
CYCLE_JSON="${RESULTS_DIR}/${CASE_NAME}-cycle.json"

MYSQL_PAYMENT_CONTAINER="${MYSQL_PAYMENT_CONTAINER:-payment-mysql-payment}"
MYSQL_PAYMENT_REPLICA_CONTAINER="${MYSQL_PAYMENT_REPLICA_CONTAINER:-payment-mysql-payment-replica}"
MYSQL_PAYMENT_ROOT_PASSWORD="${MYSQL_PAYMENT_ROOT_PASSWORD:-payment123}"

COMPOSE_ARGS=(
    -f "${ROOT_DIR}/docker/docker-compose.infra.yml"
    -f "${ROOT_DIR}/docker/docker-compose.apps.yml"
    -f "${ROOT_DIR}/docker/docker-compose.observability.yml"
    -f "${ROOT_DIR}/docker/docker-compose.benchmark.yml"
    -f "${ROOT_DIR}/docker/docker-compose.scaleout.yml"
)

dc() {
    docker compose "${COMPOSE_ARGS[@]}" "$@"
}

# ---------------------------------------------------------------------------
# 헬퍼
# ---------------------------------------------------------------------------

wait_healthy() {
    local service="$1" timeout="${2:-120}" attempt=0 cid status="unknown"
    while true; do
        cid=$(dc ps -q "${service}" 2>/dev/null | head -n1)
        if [[ -n "${cid}" ]]; then
            status=$(docker inspect --format '{{.State.Health.Status}}' "${cid}" 2>/dev/null || echo "unknown")
            [[ "${status}" == "healthy" ]] && return 0
        fi
        attempt=$((attempt + 1))
        if [[ "${attempt}" -ge "${timeout}" ]]; then
            print_error "❌ ${service} 가 시간 내 healthy 로 전환되지 않음 (마지막 상태: ${status})"
            return 1
        fi
        sleep 1
    done
}

# 스케일된 서비스 전부(expected_count 대)가 healthy 인지 확인한다.
wait_all_healthy() {
    local service="$1" expected="$2" timeout="${3:-150}" attempt=0
    while true; do
        local cids healthy_count=0
        cids=($(dc ps -q "${service}" 2>/dev/null))
        if [[ "${#cids[@]}" -eq "${expected}" ]]; then
            for cid in "${cids[@]}"; do
                local status
                status=$(docker inspect --format '{{.State.Health.Status}}' "${cid}" 2>/dev/null || echo "unknown")
                [[ "${status}" == "healthy" ]] && healthy_count=$((healthy_count + 1))
            done
            if [[ "${healthy_count}" -eq "${expected}" ]]; then
                return 0
            fi
        fi
        attempt=$((attempt + 1))
        if [[ "${attempt}" -ge "${timeout}" ]]; then
            print_error "❌ ${service} ${expected}대 전부 healthy 전환 실패 (컨테이너 ${#cids[@]}개, healthy ${healthy_count}개)"
            return 1
        fi
        sleep 1
    done
}

# Eureka 등록(status=UP) 확인 — 컨테이너 healthy 와 Eureka 등록은 별개다. payment-service 를
# user-service 보다 먼저 올리면 Eureka 클라이언트의 시작 시점 스냅샷 누락으로 checkout 이
# 일시 503 을 낸다(Task 12 실측) — user-service 를 먼저 확인해 재발을 막는다.
wait_eureka_registered() {
    local app_name="$1" timeout="${2:-60}" attempt=0
    while true; do
        if curl -s -H 'Accept: application/json' "http://localhost:8761/eureka/apps/${app_name}" 2>/dev/null \
            | grep -q '"status":"UP"'; then
            return 0
        fi
        attempt=$((attempt + 1))
        if [[ "${attempt}" -ge "${timeout}" ]]; then
            print_error "❌ ${app_name} 가 시간 내 Eureka 에 UP 으로 등록되지 않음"
            return 1
        fi
        sleep 1
    done
}

# 지정 서비스의 실행 중 컨테이너 이름을 "name1:6379,name2:6379,..." 형태로 합성한다
# (scripts/bench-cluster-check.sh 와 동일 방식).
cluster_nodes_csv() {
    local service="$1"
    local cids names=() cid name
    cids=($(dc ps -q "${service}" 2>/dev/null))
    for cid in "${cids[@]}"; do
        name=$(docker inspect --format '{{.Name}}' "${cid}" | sed 's#^/##')
        names+=("${name}")
    done
    local IFS=,
    local parts=()
    for name in "${names[@]}"; do
        parts+=("${name}:6379")
    done
    echo "${parts[*]}"
}

REPLICA_LAG_LOG=""
REPLICA_SAMPLER_PID=""

start_replica_lag_sampler() {
    REPLICA_LAG_LOG="$(mktemp "${ROOT_DIR}/results/.replica-lag.${CASE_NAME}.XXXXXX")"
    (
        # 이 서브셸은 함수가 아니라 while 루프 본문이라 'local' 을 쓸 수 없다(bash 가
        # "local: can only be used in a function" 오류를 내고도 계속 진행은 하지만,
        # 매 반복 stderr 에 잡음을 남긴다) — 평범한 변수로 둔다.
        while true; do
            raw=$(docker exec "${MYSQL_PAYMENT_REPLICA_CONTAINER}" mysql \
                -u root -p"${MYSQL_PAYMENT_ROOT_PASSWORD}" -e "SHOW REPLICA STATUS\G" 2>/dev/null \
                | awk -F': ' '/Seconds_Behind_Source/ { print $2 }')
            echo "$(date +%s) ${raw:-NULL}" >> "${REPLICA_LAG_LOG}"
            sleep "${REPLICA_SAMPLE_INTERVAL_SECONDS}"
        done
    ) &
    REPLICA_SAMPLER_PID=$!
}

# SIGTERM 는 그 순간 표본화 루프가 docker exec 자식에 블록돼 있으면 못 받을 수 있고,
# 뒤이어 종료를 wait 로 확인하는 방식은 그 확인 자체가 이 환경에서 무기한 걸리는 것을
# 실측으로 겪었다(호스트 자원이 눌린 상태로 추정 — 원인을 이 세션에서 완전히 못 좁혔다).
# 그래서 확인 없이 SIGKILL 만 쏘고 끝낸다 — kill 자체는 블로킹 시스템 콜이 아니라 이
# 호출은 걸리지 않는다. 죽었는지 확인하지 않으므로 표본화 루프가 아주 짧게 한 바퀴 더
# 돌 수는 있지만(시드가 끝난 뒤라 재시드와 겹치지 않는다), 좀비로 남는 것은 이 스크립트
# 프로세스가 끝나면 함께 정리된다.
stop_replica_lag_sampler() {
    if [[ -n "${REPLICA_SAMPLER_PID}" ]]; then
        kill -9 "${REPLICA_SAMPLER_PID}" 2>/dev/null || true
        REPLICA_SAMPLER_PID=""
    fi
}

# 표본 로그(초 단위 정수, "NULL" 은 IO 스레드 중단 등으로 조회 불가했던 표본)에서
# 개수/최소/평균/최대를 뽑아 jq 조각으로 낸다. 표본이 하나도 없으면 samples=0 만 낸다.
replica_lag_stats_json() {
    if [[ -z "${REPLICA_LAG_LOG}" || ! -s "${REPLICA_LAG_LOG}" ]]; then
        echo '{"samples":0,"min":null,"avg":null,"max":null,"null_samples":0}'
        return
    fi
    awk '
        {
            total++
            if ($2 == "NULL") { nulls++; next }
            if ($2 !~ /^[0-9]+$/) { next }
            n++
            sum += $2
            if (n == 1 || $2 < lo) { lo = $2 }
            if (n == 1 || $2 > hi) { hi = $2 }
        }
        END {
            if (n == 0) {
                printf "{\"samples\":0,\"min\":null,\"avg\":null,\"max\":null,\"null_samples\":%d}\n", nulls + 0
                exit
            }
            printf "{\"samples\":%d,\"min\":%d,\"avg\":%.2f,\"max\":%d,\"null_samples\":%d}\n", n, lo, sum / n, hi, nulls + 0
        }
    ' "${REPLICA_LAG_LOG}"
}

check_docker

# ---------------------------------------------------------------------------
# 선결 확인 — k6 / jq
# ---------------------------------------------------------------------------

if ! command -v k6 >/dev/null 2>&1; then
    print_error "❌ k6 가 설치되어 있지 않다 — https://grafana.com/docs/k6/latest/get-started/installation/"
    exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
    print_error "❌ jq 가 설치되어 있지 않다"
    exit 1
fi

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ bench-scaleout-cycle — ${CASE_NAME}"
print_section "  instances=${INSTANCES} stock_masters=${STOCK_MASTERS} dedupe_masters=${DEDUPE_MASTERS}"
print_section "  polling_route=${POLLING_ROUTE} items_per_order=${ITEMS_PER_ORDER} vendor_latency=${VENDOR_LATENCY}(${FAKE_LATENCY_MIN}~${FAKE_LATENCY_MAX}ms)"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

mkdir -p "${RESULTS_DIR}"

# ---------------------------------------------------------------------------
# (1) 스택 기동 — 관측 스택 포함. 재고/멱등 캐시 클러스터는 대수 조정이 필요해
#     여기서 올리지 않고 (2)에서 scripts/bench-redis-cluster.sh 가 전담한다.
#     user-service 를 payment-service 보다 먼저 올린다(Eureka 스냅샷 레이스 회피).
# ---------------------------------------------------------------------------

print_section "▶ (1) 스택 기동"

if ! dc up -d eureka kafka redis-dedupe redis-stock \
    mysql-payment mysql-payment-replica mysql-pg mysql-product mysql-user \
    prometheus alertmanager grafana kafka-exporter tempo loki promtail >/dev/null 2>&1; then
    print_error "❌ (1) 인프라/관측 스택 기동 실패"
    exit 1
fi

if ! wait_healthy mysql-payment-replica 90; then exit 1; fi

if ! dc up -d user-service >/dev/null 2>&1; then
    print_error "❌ (1) user-service 기동 실패"
    exit 1
fi
if ! wait_healthy user-service 90 || ! wait_eureka_registered "USER-SERVICE" 60; then
    exit 1
fi

if ! dc up -d product-service >/dev/null 2>&1; then
    print_error "❌ (1) product-service 기동 실패"
    exit 1
fi
if ! wait_healthy product-service 90; then exit 1; fi

print_info "✅ (1) 인프라/관측/user-service/product-service 기동 완료"
echo ""

# ---------------------------------------------------------------------------
# (2) 클러스터 구성 — 재고 캐시(대수는 조건 값) + 멱등 저장소(고정 3), 이어서
#     payment-service/pg-service 를 조건 값 env 로 재기동한다.
# ---------------------------------------------------------------------------

print_section "▶ (2) 클러스터 구성 — 재고 캐시 ${STOCK_MASTERS}대 / 멱등 저장소 ${DEDUPE_MASTERS}대"

if ! bash "${ROOT_DIR}/scripts/bench-redis-cluster.sh" --store stock --masters "${STOCK_MASTERS}"; then
    print_error "❌ (2) 재고 캐시 클러스터 구성 실패"
    exit 1
fi
if ! bash "${ROOT_DIR}/scripts/bench-redis-cluster.sh" --store dedupe --masters "${DEDUPE_MASTERS}"; then
    print_error "❌ (2) 멱등 저장소 클러스터 구성 실패"
    exit 1
fi

STOCK_NODES_CSV=$(cluster_nodes_csv redis-stock-cluster)
DEDUPE_NODES_CSV=$(cluster_nodes_csv redis-idempotency-cluster)
if [[ -z "${STOCK_NODES_CSV}" || -z "${DEDUPE_NODES_CSV}" ]]; then
    print_error "❌ (2) 클러스터 노드 목록 계산 실패"
    exit 1
fi
print_info "  재고 캐시 노드: ${STOCK_NODES_CSV}"
print_info "  멱등 저장소 노드: ${DEDUPE_NODES_CSV}"

if [[ "${POLLING_ROUTE}" == "on" ]]; then
    REPLICA_ENABLED="true"
else
    REPLICA_ENABLED="false"
fi

print_section "  payment-service 재기동 — 인스턴스 ${INSTANCES}대, 폴링 라우팅=${POLLING_ROUTE}"
export REDIS_STOCK_CLUSTER_NODES="${STOCK_NODES_CSV}"
export SPRING_DATA_REDIS_CLUSTER_NODES="${DEDUPE_NODES_CSV}"
export PAYMENT_DATASOURCE_REPLICA_ENABLED="${REPLICA_ENABLED}"
export RECONCILER_TIMEOUT="${RECONCILER_TIMEOUT}"
export RECONCILER_SCAN_MS="${RECONCILER_SCAN_MS}"
export HIKARI_MAX_POOL="${HIKARI_MAX_POOL}"
if ! dc up -d --scale payment-service="${INSTANCES}" --force-recreate payment-service >/dev/null 2>&1; then
    print_error "❌ (2) payment-service 재기동 실패"
    exit 1
fi
if ! wait_all_healthy payment-service "${INSTANCES}" 150; then exit 1; fi
if ! wait_eureka_registered "PAYMENT-SERVICE" 60; then exit 1; fi
print_info "✅ payment-service ${INSTANCES}대 healthy + Eureka 등록 확인"

print_section "  pg-service 재기동 — 벤더 지연=${VENDOR_LATENCY}(${FAKE_LATENCY_MIN}~${FAKE_LATENCY_MAX}ms)"
export FAKE_LATENCY_MIN="${FAKE_LATENCY_MIN}"
export FAKE_LATENCY_MAX="${FAKE_LATENCY_MAX}"
export FAKE_FAIL_RATE="${FAKE_FAIL_RATE}"
if ! dc up -d --force-recreate pg-service >/dev/null 2>&1; then
    print_error "❌ (2) pg-service 재기동 실패"
    exit 1
fi
if ! wait_healthy pg-service 120; then exit 1; fi
print_info "✅ pg-service healthy"

# --no-deps 필수 — gateway 는 payment-service 를 depends_on 으로 갖는데, docker compose 는
# --scale 값을 이 호출 하나에만 두고 영구화하지 않는다. --no-deps 없이 이 호출을 실행하면
# compose 가 payment-service 를 기본 대수(1)로 되돌리면서 방금 위에서 스케일한 2번째 이상
# 인스턴스를 즉시 삭제한다(정지가 아니라 삭제라 docker ps -a 에도 남지 않는다) — 이 사이클
# 러너를 실제로 돌려 "인스턴스 2대 사이클인데 payment-service 가 1대로 줄어 있다"는 증상을
# 실측으로 확인해 원인을 좁혔다. gateway 의 의존 서비스(eureka/payment-service/pg-service/
# product-service/user-service)는 이 지점에 전부 이미 healthy 로 떠 있으므로 의존 해석 없이
# gateway 하나만 올리면 된다.
if ! dc up -d --no-deps gateway >/dev/null 2>&1; then
    print_error "❌ (2) gateway 기동 실패"
    exit 1
fi
if ! wait_healthy gateway 90; then exit 1; fi
print_info "✅ gateway healthy"
echo ""

# ---------------------------------------------------------------------------
# (3) 시드
# ---------------------------------------------------------------------------

print_section "▶ (3) 재고 시드 — ${PRODUCT_COUNT}종, stock=${BENCH_STOCK}"

STOCK_CID=$(dc ps -q redis-stock-cluster 2>/dev/null | head -n1)
STOCK_CONTAINER_NAME=$(docker inspect --format '{{.Name}}' "${STOCK_CID}" 2>/dev/null | sed 's#^/##')
if [[ -z "${STOCK_CONTAINER_NAME}" ]]; then
    print_error "❌ (3) redis-stock-cluster 컨테이너를 찾지 못함"
    exit 1
fi

if ! PRODUCT_COUNT="${PRODUCT_COUNT}" PRODUCT_ID_BASE="${PRODUCT_ID_BASE}" BENCH_STOCK="${BENCH_STOCK}" \
    REDIS_STOCK_CONTAINER="${STOCK_CONTAINER_NAME}" \
    bash "${ROOT_DIR}/scripts/bench-seed-stock.sh"; then
    print_error "❌ (3) 재고 시드 실패"
    exit 1
fi
echo ""

# ---------------------------------------------------------------------------
# (4) 부하 — 복제 지연 표본화는 부하 시작부터 정합 검증이 끝날 때까지 계속한다.
# ---------------------------------------------------------------------------

print_section "▶ (4) 부하 실행 — CASE_NAME=${CASE_NAME}"

start_replica_lag_sampler

LOAD_START_EPOCH=$(date +%s)
set +e
(
    cd "${ROOT_DIR}"
    # shellcheck disable=SC2086 — K6_EXTRA_ARGS 는 "-e KEY=VALUE" 형태를 공백으로 여러 개
    # 이어붙이는 용도라 단어 분리가 의도된 동작이다.
    k6 run \
        --tag "testid=${CASE_NAME}" \
        -e "BASE_URL=${BASE_URL}" \
        -e "CASE_NAME=${CASE_NAME}" \
        -e "ITEMS_PER_ORDER=${ITEMS_PER_ORDER}" \
        -e "PRODUCT_COUNT=${PRODUCT_COUNT}" \
        -e "PRODUCT_ID_BASE=${PRODUCT_ID_BASE}" \
        ${K6_EXTRA_ARGS} \
        "${SCRIPT_DIR}/k6/async-payment.js"
)
K6_EXIT=$?
set -e
LOAD_END_EPOCH=$(date +%s)
LOAD_DURATION_SEC=$((LOAD_END_EPOCH - LOAD_START_EPOCH))

if [[ "${K6_EXIT}" -ne 0 && "${K6_EXIT}" -ne 99 ]]; then
    print_error "❌ (4) k6 실행 오류 (exit ${K6_EXIT})"
    stop_replica_lag_sampler
    exit 1
fi
if [[ "${K6_EXIT}" -eq 99 ]]; then
    print_warning "⚠ k6 threshold 위반(exit 99) — 결과는 생성됨, 계속 진행"
fi

K6_RESULT_JSON="${RESULTS_DIR}/${CASE_NAME}.json"
if [[ ! -f "${K6_RESULT_JSON}" ]]; then
    print_error "❌ (4) 결과 파일 없음: ${K6_RESULT_JSON}"
    stop_replica_lag_sampler
    exit 1
fi
print_info "✅ (4) 부하 완료 (${LOAD_DURATION_SEC}초) — ${K6_RESULT_JSON}"
echo ""

# ---------------------------------------------------------------------------
# (5) 종결 대기 + 정합 검증 — verify-settlement.sh 종료 코드로 판정을 받는다.
#     INCONCLUSIVE(2) 는 유한 재시도, MISMATCH(3)/그 외는 즉시 중단.
# ---------------------------------------------------------------------------

print_section "▶ (5) 종결 대기 + 정합 검증 (scripts/k6/verify-settlement.sh)"

call_verify() {
    local settle_wait="$1"
    SETTLE_WAIT_SECONDS="${settle_wait}" \
    RECONCILER_TIMEOUT="${RECONCILER_TIMEOUT}" \
    RECONCILER_SCAN_MS="${RECONCILER_SCAN_MS}" \
    CASE_NAME="${CASE_NAME}" \
    RESULTS_DIR="${RESULTS_DIR}" \
    PRODUCT_COUNT="${PRODUCT_COUNT}" \
    PRODUCT_ID_BASE="${PRODUCT_ID_BASE}" \
    REDIS_STOCK_CONTAINER="${STOCK_CONTAINER_NAME}" \
    bash "${ROOT_DIR}/scripts/k6/verify-settlement.sh"
    return $?
}

# call_verify 의 종료 코드(2/3/그 외)는 이 러너의 정상 제어 흐름이라, 전역 set -e(라인
# ~456) 아래서 그대로 실행하면 errexit 가 즉시 스크립트를 죽여 재시도 루프·복제 지연
# 표본화 정지·결과 기록·재구성이 전부 스킵된다(실측 중 발견 — INCONCLUSIVE 첫 판정에서
# 스크립트가 그 자리에서 죽는 것을 확인). `cmd || VERIFY_EXIT=$?` 형태로 좌변에 둬야
# errexit 예외 대상이 된다.
VERIFY_EXIT=0
RETRY_COUNT=0
call_verify "" || VERIFY_EXIT=$?

while [[ "${VERIFY_EXIT}" -eq 2 && "${RETRY_COUNT}" -lt "${INCONCLUSIVE_MAX_RETRIES}" ]]; do
    RETRY_COUNT=$((RETRY_COUNT + 1))
    print_warning "⚠️  판단 보류(INCONCLUSIVE) — ${INCONCLUSIVE_RETRY_WAIT_SECONDS}초 대기 후 재검증 (${RETRY_COUNT}/${INCONCLUSIVE_MAX_RETRIES})"
    sleep "${INCONCLUSIVE_RETRY_WAIT_SECONDS}"
    VERIFY_EXIT=0
    call_verify 0 || VERIFY_EXIT=$?
done

stop_replica_lag_sampler
SETTLE_END_EPOCH=$(date +%s)

VERDICT_JSON="${RESULTS_DIR}/${CASE_NAME}-verdict.json"
VERDICT="UNKNOWN"
VERDICT_REASON=""
if [[ -f "${VERDICT_JSON}" ]]; then
    VERDICT=$(jq -r '.verdict // "UNKNOWN"' "${VERDICT_JSON}")
    VERDICT_REASON=$(jq -r '.reason // ""' "${VERDICT_JSON}")
fi

CYCLE_EXIT=0
OUTCOME="FAILED"
RESET_STATUS="SKIPPED"

case "${VERIFY_EXIT}" in
    0)
        print_info "✅ (5) 정합 검증 통과(PASS) — ${VERDICT_REASON}"
        OUTCOME="SUCCESS"
        CYCLE_EXIT=0
        ;;
    2)
        print_error "❌ (5) 판단 보류 소진(INCONCLUSIVE, 재시도 ${RETRY_COUNT}/${INCONCLUSIVE_MAX_RETRIES}) — 캐시를 비우지 않고 사이클 실패"
        CYCLE_EXIT=2
        ;;
    3)
        print_error "❌ (5) 정합 불일치(MISMATCH) — 즉시 중단, 캐시를 비우지 않는다"
        CYCLE_EXIT=3
        ;;
    *)
        print_error "❌ (5) 정합 검증이 접속·전제 실패(exit ${VERIFY_EXIT}) 또는 알 수 없는 코드로 끝남 — 통과로 읽지 않는다"
        CYCLE_EXIT=4
        ;;
esac
echo ""

# ---------------------------------------------------------------------------
# (6) 재구성 — 통과했을 때만. 실패로 멈춘 사이클의 잔류는 사람이 scripts/bench-cycle-reset.sh
#     로 직접 정리한다.
# ---------------------------------------------------------------------------

if [[ "${OUTCOME}" == "SUCCESS" ]]; then
    print_section "▶ (6) 재구성 (scripts/bench-cycle-reset.sh)"
    if RECONCILER_TIMEOUT="${RECONCILER_TIMEOUT}" RECONCILER_SCAN_MS="${RECONCILER_SCAN_MS}" \
        PRODUCT_COUNT="${PRODUCT_COUNT}" PRODUCT_ID_BASE="${PRODUCT_ID_BASE}" BENCH_STOCK="${BENCH_STOCK}" \
        REDIS_STOCK_CONTAINER="${STOCK_CONTAINER_NAME}" \
        bash "${ROOT_DIR}/scripts/bench-cycle-reset.sh"; then
        RESET_STATUS="DONE"
        print_info "✅ (6) 재구성 완료"
    else
        RESET_STATUS="FAILED"
        print_error "❌ (6) 재구성 실패 — 잔류 상태를 사람이 직접 확인해야 한다"
        CYCLE_EXIT=1
    fi
else
    print_section "▶ (6) 재구성 — 스킵 (정합 판정이 통과가 아니라 캐시를 비우지 않는다)"
    print_warning "  잔류를 치우려면 사람이 scripts/bench-cycle-reset.sh 를 직접 돌린다"
fi
echo ""

# ---------------------------------------------------------------------------
# (7) 결과 기록 — results/<CASE_NAME>-cycle.json
# ---------------------------------------------------------------------------

print_section "▶ (7) 결과 기록 — ${CYCLE_JSON}"

K6_RESULT_JSON="${RESULTS_DIR}/${CASE_NAME}.json"
CONFIRM_COUNT=0
DB_DONE_COUNT=0
if [[ -f "${K6_RESULT_JSON}" ]]; then
    CONFIRM_COUNT=$(jq -r '.metrics.confirm_requests_count // 0' "${K6_RESULT_JSON}")
fi
if [[ -f "${VERDICT_JSON}" ]]; then
    DB_DONE_COUNT=$(jq -r '.counts.db_done // 0' "${VERDICT_JSON}")
fi

TOTAL_WALL_SEC=$((SETTLE_END_EPOCH - LOAD_START_EPOCH))
CONFIRM_PER_SEC="0"
E2E_PER_SEC="0"
if [[ "${LOAD_DURATION_SEC}" -gt 0 ]]; then
    CONFIRM_PER_SEC=$(awk -v c="${CONFIRM_COUNT}" -v d="${LOAD_DURATION_SEC}" 'BEGIN { printf "%.3f", c / d }')
fi
if [[ "${OUTCOME}" == "SUCCESS" && "${TOTAL_WALL_SEC}" -gt 0 ]]; then
    E2E_PER_SEC=$(awk -v c="${DB_DONE_COUNT}" -v d="${TOTAL_WALL_SEC}" 'BEGIN { printf "%.3f", c / d }')
fi

POLL_LATENCY_JSON=$(jq -c '.metrics.http_req_duration_poll // null' "${K6_RESULT_JSON}" 2>/dev/null || echo null)
E2E_LATENCY_JSON=$(jq -c '.metrics.e2e_completion_ms // null' "${K6_RESULT_JSON}" 2>/dev/null || echo null)
REPLICA_LAG_JSON=$(replica_lag_stats_json)

jq -n \
    --arg case_name "${CASE_NAME}" \
    --arg created_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --argjson instances "${INSTANCES}" \
    --argjson stock_masters "${STOCK_MASTERS}" \
    --argjson dedupe_masters "${DEDUPE_MASTERS}" \
    --arg polling_route "${POLLING_ROUTE}" \
    --argjson items_per_order "${ITEMS_PER_ORDER}" \
    --arg vendor_latency "${VENDOR_LATENCY}" \
    --argjson fake_latency_min "${FAKE_LATENCY_MIN}" \
    --argjson fake_latency_max "${FAKE_LATENCY_MAX}" \
    --argjson product_count "${PRODUCT_COUNT}" \
    --argjson reconciler_timeout_s "${RECONCILER_TIMEOUT}" \
    --argjson reconciler_scan_ms "${RECONCILER_SCAN_MS}" \
    --argjson hikari_max_pool "${HIKARI_MAX_POOL}" \
    --argjson confirm_count "${CONFIRM_COUNT}" \
    --argjson db_done_count "${DB_DONE_COUNT}" \
    --argjson load_duration_sec "${LOAD_DURATION_SEC}" \
    --argjson total_wall_sec "${TOTAL_WALL_SEC}" \
    --arg confirm_per_sec "${CONFIRM_PER_SEC}" \
    --arg e2e_per_sec "${E2E_PER_SEC}" \
    --argjson poll_latency_ms "${POLL_LATENCY_JSON}" \
    --argjson e2e_latency_ms "${E2E_LATENCY_JSON}" \
    --argjson replica_lag_seconds "${REPLICA_LAG_JSON}" \
    --arg verdict "${VERDICT}" \
    --argjson verify_exit_code "${VERIFY_EXIT}" \
    --arg verdict_reason "${VERDICT_REASON}" \
    --argjson inconclusive_retries "${RETRY_COUNT}" \
    --arg outcome "${OUTCOME}" \
    --arg reset_status "${RESET_STATUS}" \
    '{
        case_name: $case_name,
        created_at: $created_at,
        conditions: {
            instances: $instances,
            stock_masters: $stock_masters,
            dedupe_masters: $dedupe_masters,
            polling_route: $polling_route,
            items_per_order: $items_per_order,
            vendor_latency: $vendor_latency,
            fake_latency_min_ms: $fake_latency_min,
            fake_latency_max_ms: $fake_latency_max,
            product_count: $product_count,
            reconciler_timeout_s: $reconciler_timeout_s,
            reconciler_scan_ms: $reconciler_scan_ms,
            hikari_max_pool: $hikari_max_pool
        },
        throughput: {
            confirm_count: $confirm_count,
            db_done_count: $db_done_count,
            load_duration_sec: $load_duration_sec,
            total_wall_sec: $total_wall_sec,
            confirm_per_sec: ($confirm_per_sec | tonumber),
            e2e_done_per_sec: ($e2e_per_sec | tonumber)
        },
        latency_ms: {
            poll_response: $poll_latency_ms,
            e2e_completion: $e2e_latency_ms
        },
        replica_lag_seconds: $replica_lag_seconds,
        settlement: {
            verdict: $verdict,
            verify_exit_code: $verify_exit_code,
            reason: $verdict_reason,
            inconclusive_retries: $inconclusive_retries
        },
        outcome: $outcome,
        reset_status: $reset_status
    }' > "${CYCLE_JSON}"

print_info "✅ (7) 결과 기록 완료 — ${CYCLE_JSON}"

if [[ -n "${REPLICA_LAG_LOG}" ]]; then
    rm -f "${REPLICA_LAG_LOG}"
fi

echo ""
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
if [[ "${CYCLE_EXIT}" -eq 0 ]]; then
    print_info "✅ bench-scaleout-cycle 완료 — ${CASE_NAME} (outcome=${OUTCOME})"
else
    print_error "❌ bench-scaleout-cycle 실패 — ${CASE_NAME} (exit ${CYCLE_EXIT}, outcome=${OUTCOME}, reset=${RESET_STATUS})"
fi
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

exit "${CYCLE_EXIT}"
