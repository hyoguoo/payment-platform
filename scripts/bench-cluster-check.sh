#!/usr/bin/env bash
# bench-cluster-check.sh — 재고 캐시 클러스터(redis-stock-cluster) 라이브 점검.
#
# 스크립트 5종(선차감 / 격리 복구 조건부 보상 / 거절 전용 되돌리기 / 주문 선점 획득 / 해제)이
# 클러스터 구성에서 실제로 도는지, 상품 하나에 딸린 키가 같은 슬롯에 모이는지, 상품 종류가
# 노드에 고르게 퍼지는지를 실측한다.
#
# 다섯 경로 중 선차감/주문 선점 획득·해제/거절 전용 되돌리기 넷은 payment-service 를
# 클러스터에 붙인 뒤 실제 checkout+confirm API 로 흘려 확인한다(정상 경로 1건 + 다중 상품
# 중 하나가 품절인 거절 경로 1건 — 둘 다 실제 애플리케이션 코드 경로다). 격리 복구 조건부
# 보상은 정상 흐름에서 타지 않는 경로(FCG 실패·격리 종결 전용)라 그 경로만 EVAL 로 직접
# 태운다 — qty=0 으로 호출해 실제 재고 수량은 건드리지 않는다.
#
# 한 상품에 딸린 키(stock:{id} / decrement:done:{id}:x / compensation:done:{id}:x)가 같은
# 슬롯에 모이는지는 CLUSTER KEYSLOT 으로 시드된 상품 전부(PRODUCT_COUNT)를 대조한다 — 해시태그가
# 깨지면 EVAL 자체가 CROSSSLOT 오류로 실패하므로 다섯 경로 점검과 서로 교차 확인된다.
#
# 사용법:
#   ./scripts/bench-cluster-check.sh
#   SKEW_THRESHOLD_PCT=60 ./scripts/bench-cluster-check.sh
#
# 환경 변수:
#   PRODUCT_COUNT / PRODUCT_ID_BASE / BENCH_STOCK — scripts/bench-seed-stock.sh 와 동일(기본 100/1000/1000만)
#   PROBE_PRODUCT_A   — 정상 경로(단일 상품) 확인용 상품 id (기본 PRODUCT_ID_BASE)
#   PROBE_PRODUCT_B1  — 거절 경로 중 재고가 충분해 직접 차감됐다가 되돌려지는 상품 id (기본 PRODUCT_ID_BASE+1)
#   PROBE_PRODUCT_B2  — 거절 경로에서 품절로 강제해 거절을 유발하는 상품 id (기본 PRODUCT_ID_BASE+2)
#   PROBE_PRODUCT_C   — 격리 복구 조건부 보상 EVAL 직접 확인용 상품 id (기본 PRODUCT_ID_BASE+3)
#   USER_ID           — checkout 에 쓸 사용자 id (기본 1 — mysql-user 시드 사용자)
#   SKEW_THRESHOLD_PCT — 노드별 키 분포가 이상적 평균에서 벗어나도 되는 최대 편차 비율 (기본 50)
#   MYSQL_PAYMENT_CONTAINER / DB / USER / PASSWORD — mysql-payment 접속 정보 (테스트 행 정리용)
#
# 선행 조건:
#   - docker compose ... -f docker-compose.scaleout.yml up -d 로 스택이 떠 있다
#   - redis-stock-cluster 가 이미 원하는 대수로 구성돼 있다
#     (scripts/bench-redis-cluster.sh --store stock --masters N)
#   - product RDB 에 PRODUCT_COUNT 종 상품이 시드돼 있다(없으면 이 스크립트가 재시드한다)
#
# 이 스크립트가 하는 일:
#   1. redis-stock-cluster 컨테이너 목록으로 payment-service 를 클러스터 연결로 재기동
#   2. user-service 가 없으면 기동(checkout 이 userId 검증을 위해 호출)
#   3. scripts/bench-seed-stock.sh 로 클러스터에 상품 전부를 상수로 시드
#   4. 정상 경로 1건(단일 상품) + 거절 경로 1건(다중 상품 중 하나 품절) 실행
#   5. 격리 복구 조건부 보상을 EVAL 로 직접 실행(OK/NO_DECREMENT 두 분기)
#   6. 시드된 상품 전부의 해시태그 슬롯 일치 + 노드별 분포 편차 확인
#   7. 테스트로 남긴 payment 여섯 테이블 행 삭제, 캐시 값 원복, payment-service 를 단독 연결로 원복
#
# 종료 코드:
#   0 — 다섯 경로 전부 정상, 상품 전부 슬롯 일치, 노드 분포 편차가 임계 이하
#   1 — 선결 조건 미충족 / 접속 실패(캐시를 건드리지 않고 즉시 종료, 원복 불필요)
#   2 — 다섯 경로 중 하나 이상이 기대와 다른 결과
#   3 — 상품 슬롯 불일치(해시태그 깨짐) 발견
#   4 — 노드별 키 분포 편차가 임계 초과

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
LUA_DIR="${ROOT_DIR}/payment-service/src/main/resources/lua"

# shellcheck source=common.sh
source "${ROOT_DIR}/scripts/common.sh"

PRODUCT_COUNT="${PRODUCT_COUNT:-100}"
PRODUCT_ID_BASE="${PRODUCT_ID_BASE:-1000}"
BENCH_STOCK="${BENCH_STOCK:-10000000}"

PROBE_PRODUCT_A="${PROBE_PRODUCT_A:-$((PRODUCT_ID_BASE + 0))}"
PROBE_PRODUCT_B1="${PROBE_PRODUCT_B1:-$((PRODUCT_ID_BASE + 1))}"
PROBE_PRODUCT_B2="${PROBE_PRODUCT_B2:-$((PRODUCT_ID_BASE + 2))}"
PROBE_PRODUCT_C="${PROBE_PRODUCT_C:-$((PRODUCT_ID_BASE + 3))}"

USER_ID="${USER_ID:-1}"
SKEW_THRESHOLD_PCT="${SKEW_THRESHOLD_PCT:-50}"

MYSQL_CONTAINER="${MYSQL_PAYMENT_CONTAINER:-payment-mysql-payment}"
MYSQL_DB="${MYSQL_PAYMENT_DB:-payment-platform}"
MYSQL_USER="${MYSQL_PAYMENT_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PAYMENT_PASSWORD:-payment123}"

COMPOSE_ARGS=(
    -f "${ROOT_DIR}/docker/docker-compose.infra.yml"
    -f "${ROOT_DIR}/docker/docker-compose.apps.yml"
    -f "${ROOT_DIR}/docker/docker-compose.scaleout.yml"
)

dc() {
    docker compose "${COMPOSE_ARGS[@]}" "$@"
}

mysql_query() {
    docker exec -i "${MYSQL_CONTAINER}" mysql \
        -u "${MYSQL_USER}" -p"${MYSQL_PASSWORD}" \
        -D "${MYSQL_DB}" -N -B -e "$1" 2>/dev/null
}

wait_healthy() {
    local service="$1" timeout="${2:-90}" attempt=0 cid status="unknown"
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

# Eureka 서버 등록(status=UP)까지 확인 — 컨테이너 healthcheck 통과와 Eureka 등록은 별개다.
# payment-service 를 user-service 보다 먼저 재기동하면, payment-service 의 Eureka 클라이언트가
# 시작 시점에 받는 최초 레지스트리 스냅샷에 user-service 가 없어 checkout 이 일시적으로
# 503(user-service 사용 불가)을 낸다 — 순서를 user-service 먼저로 두고 이 확인으로 못박는다.
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

check_docker

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ bench-cluster-check — 재고 캐시 클러스터 라이브 점검"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# ---------------------------------------------------------------------------
# 0. 선결 조건 — redis-stock-cluster 컨테이너 존재 확인, 노드 목록 계산
# ---------------------------------------------------------------------------

CLUSTER_CIDS=($(dc ps -q redis-stock-cluster 2>/dev/null))
if [[ "${#CLUSTER_CIDS[@]}" -eq 0 ]]; then
    print_error "❌ redis-stock-cluster 컨테이너가 없다 — 먼저 실행하세요:"
    echo "    ./scripts/bench-redis-cluster.sh --store stock --masters 2"
    exit 1
fi

CLUSTER_NAMES=()
for cid in "${CLUSTER_CIDS[@]}"; do
    name=$(docker inspect --format '{{.Name}}' "${cid}" | sed 's#^/##')
    CLUSTER_NAMES+=("${name}")
done
MASTER_COUNT="${#CLUSTER_NAMES[@]}"
CLUSTER_CID="${CLUSTER_CIDS[0]}"
NODES_CSV=$(IFS=,; parts=(); for n in "${CLUSTER_NAMES[@]}"; do parts+=("${n}:6379"); done; echo "${parts[*]}")

print_info "✅ redis-stock-cluster ${MASTER_COUNT}대 확인 — ${CLUSTER_NAMES[*]}"
print_info "  노드 목록: ${NODES_CSV}"
echo ""

# 원복은 스크립트 종료 시 항상 시도한다 — 여기부터는 트랩으로 정리를 보장한다.
STARTED_USER_SERVICE=false
cleanup() {
    print_section "▶ 정리 — payment-service 원복 + 테스트 흔적 삭제"

    if [[ -n "${TEST_ORDER_IDS:-}" ]]; then
        local order_list
        # shellcheck disable=SC2086 — TEST_ORDER_IDS 는 이 스크립트가 내부에서만 채우는
        # 공백 구분 orderId 목록이라 단어 분리가 의도된 동작이다.
        order_list=$(printf "'%s'," ${TEST_ORDER_IDS} | sed 's/,$//')
        for t in payment_event payment_order payment_outbox payment_history payment_event_dedupe stock_hold_record; do
            mysql_query "DELETE FROM ${t} WHERE order_id IN (${order_list});" >/dev/null
        done
        print_info "  테스트 주문 ${TEST_ORDER_COUNT:-0}건의 payment 여섯 테이블 행 삭제"
    fi

    # 되돌리기가 실패했거나 스킵된 경우를 대비해 프로브 상품 캐시 값을 상수로 강제 복원
    docker exec "${CLUSTER_CID}" redis-cli -c set "stock:{${PROBE_PRODUCT_A}}" "${BENCH_STOCK}" >/dev/null 2>&1 || true
    docker exec "${CLUSTER_CID}" redis-cli -c set "stock:{${PROBE_PRODUCT_B1}}" "${BENCH_STOCK}" >/dev/null 2>&1 || true
    docker exec "${CLUSTER_CID}" redis-cli -c set "stock:{${PROBE_PRODUCT_B2}}" "${BENCH_STOCK}" >/dev/null 2>&1 || true
    docker exec "${CLUSTER_CID}" redis-cli -c del "decrement:done:{${PROBE_PRODUCT_C}}:cluster-check-comp1" >/dev/null 2>&1 || true
    docker exec "${CLUSTER_CID}" redis-cli -c del "compensation:done:{${PROBE_PRODUCT_C}}:cluster-check-comp1" >/dev/null 2>&1 || true
    print_info "  프로브 상품(${PROBE_PRODUCT_A}/${PROBE_PRODUCT_B1}/${PROBE_PRODUCT_B2}) 캐시 값 상수 복원"

    export REDIS_STOCK_CLUSTER_NODES=""
    dc up -d --force-recreate payment-service >/dev/null 2>&1 || true
    wait_healthy payment-service 90 || print_warning "  ⚠️ payment-service 원복 재기동 확인 실패 — 수동 확인 필요"
    print_info "  payment-service 단독 Redis 연결로 원복"

    if [[ "${STARTED_USER_SERVICE}" == "true" ]]; then
        dc stop user-service >/dev/null 2>&1 || true
        print_info "  이 스크립트가 기동한 user-service 정지"
    fi
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# 1. user-service 확인/기동 (checkout 이 userId 검증을 위해 호출)
#    payment-service 보다 먼저 확인한다 — 순서가 반대면 payment-service 의 Eureka 클라이언트가
#    시작 시점 레지스트리 스냅샷에 user-service 를 못 담아 checkout 이 일시적으로 503 을 낸다.
# ---------------------------------------------------------------------------

print_section "▶ (1) user-service 확인"
EXISTING_USER_CID=$(dc ps -q user-service 2>/dev/null | head -n1)
if [[ -z "${EXISTING_USER_CID}" ]]; then
    if ! dc up -d user-service >/dev/null 2>&1; then
        print_error "❌ (1) user-service 기동 실패"
        exit 1
    fi
    STARTED_USER_SERVICE=true
fi
if ! wait_healthy user-service 90; then
    exit 1
fi
if ! wait_eureka_registered "USER-SERVICE" 60; then
    exit 1
fi
print_info "✅ (1) user-service 확인 완료 (healthy + Eureka UP)"
echo ""

# ---------------------------------------------------------------------------
# 2. payment-service 를 클러스터 연결로 재기동
# ---------------------------------------------------------------------------

print_section "▶ (2) payment-service 를 재고 캐시 클러스터에 연결"

export REDIS_STOCK_CLUSTER_NODES="${NODES_CSV}"
if ! dc up -d --force-recreate payment-service >/dev/null 2>&1; then
    print_error "❌ (2) payment-service 재기동 실패"
    exit 1
fi
if ! wait_healthy payment-service 90; then
    exit 1
fi
PS_CID=$(dc ps -q payment-service | head -n1)
print_info "✅ (2) payment-service 클러스터 연결로 재기동 완료 (${PS_CID:0:12})"
echo ""

# ---------------------------------------------------------------------------
# 3. 상품 전부를 클러스터에 시드 (product RDB + redis-stock-cluster)
# ---------------------------------------------------------------------------

print_section "▶ (3) 상품 ${PRODUCT_COUNT}종을 재고 캐시 클러스터에 시드"
if ! PRODUCT_COUNT="${PRODUCT_COUNT}" PRODUCT_ID_BASE="${PRODUCT_ID_BASE}" BENCH_STOCK="${BENCH_STOCK}" \
    REDIS_STOCK_CONTAINER="${CLUSTER_NAMES[0]}" \
    bash "${ROOT_DIR}/scripts/bench-seed-stock.sh" >/dev/null; then
    print_error "❌ (3) 클러스터 시드 실패"
    exit 1
fi
print_info "✅ (3) 시드 완료"
echo ""

TEST_ORDER_IDS=""
TEST_ORDER_COUNT=0
PATH_FAILURES=0

record_order_id() {
    TEST_ORDER_IDS="${TEST_ORDER_IDS} $1"
    TEST_ORDER_COUNT=$((TEST_ORDER_COUNT + 1))
}

# 응답 본문(단순 평면 JSON)에서 필드값을 뽑는다 — payment-service 컨테이너에 jq 미설치.
extract_field() {
    echo "$1" | sed -n "s/.*\"$2\":\"\{0,1\}\([^,\"}]*\)\"\{0,1\}.*/\1/p" | head -1
}

http_post() {
    # $1=path $2=json body → stdout: "<body>\n<status>"
    # uuidgen 사용 — macOS(BSD) date 는 %N(나노초)을 지원하지 않아 같은 초 안의 호출이
    # idempotency key 충돌(200 duplicate)을 낼 수 있다.
    docker exec "${PS_CID}" curl -s -w '\n%{http_code}' -X POST "http://localhost:8080$1" \
        -H "Content-Type: application/json" -H "Idempotency-Key: cluster-check-$(uuidgen)" -d "$2"
}

# ---------------------------------------------------------------------------
# 4. 정상 경로(선차감 / 주문 선점 획득 · 해제) — 단일 상품 confirm
# ---------------------------------------------------------------------------

print_section "▶ (4) 정상 경로 — 단일 상품 checkout + confirm"
print_section "  (선차감 / 주문 선점 획득 / 주문 선점 해제 세 경로)"

CHECKOUT_A_RAW=$(http_post "/api/v1/payments/checkout" \
    "{\"userId\":${USER_ID},\"orderedProductList\":[{\"productId\":${PROBE_PRODUCT_A},\"quantity\":1}],\"gatewayType\":\"TOSS\"}")
CHECKOUT_A_STATUS="${CHECKOUT_A_RAW##*$'\n'}"
CHECKOUT_A_BODY="${CHECKOUT_A_RAW%$'\n'*}"

if [[ "${CHECKOUT_A_STATUS}" != "201" ]]; then
    print_error "❌ (4) checkout 실패 — status=${CHECKOUT_A_STATUS} body=${CHECKOUT_A_BODY}"
    PATH_FAILURES=$((PATH_FAILURES + 1))
else
    ORDER_A=$(extract_field "${CHECKOUT_A_BODY}" "orderId")
    AMOUNT_A=$(extract_field "${CHECKOUT_A_BODY}" "totalAmount")
    record_order_id "${ORDER_A}"

    CONFIRM_A_RAW=$(docker exec "${PS_CID}" curl -s -w '\n%{http_code}' -X POST "http://localhost:8080/api/v1/payments/confirm" \
        -H "Content-Type: application/json" \
        -d "{\"userId\":${USER_ID},\"orderId\":\"${ORDER_A}\",\"amount\":${AMOUNT_A},\"paymentKey\":\"cluster-check-key-a\",\"gatewayType\":\"TOSS\"}")
    CONFIRM_A_STATUS="${CONFIRM_A_RAW##*$'\n'}"

    if [[ "${CONFIRM_A_STATUS}" != "202" ]]; then
        print_error "❌ (4) confirm 기대 밖 결과 — status=${CONFIRM_A_STATUS} (기대 202)"
        PATH_FAILURES=$((PATH_FAILURES + 1))
    else
        STOCK_A_AFTER=$(docker exec "${CLUSTER_CID}" redis-cli -c get "stock:{${PROBE_PRODUCT_A}}")
        EXPECTED_A=$((BENCH_STOCK - 1))
        if [[ "${STOCK_A_AFTER}" == "${EXPECTED_A}" ]]; then
            print_info "✅ (4) 정상 경로 통과 — orderId=${ORDER_A} confirm=202, stock:{${PROBE_PRODUCT_A}}=${STOCK_A_AFTER}(기대 ${EXPECTED_A})"
        else
            print_error "❌ (4) 선차감 반영값 불일치 — stock:{${PROBE_PRODUCT_A}}=${STOCK_A_AFTER} (기대 ${EXPECTED_A})"
            PATH_FAILURES=$((PATH_FAILURES + 1))
        fi
    fi
fi
echo ""

# ---------------------------------------------------------------------------
# 5. 거절 경로(거절 전용 되돌리기) — 다중 상품 중 하나를 품절로 강제
# ---------------------------------------------------------------------------

print_section "▶ (5) 거절 경로 — 다중 상품 중 하나 품절 → 거절 전용 되돌리기"

docker exec "${CLUSTER_CID}" redis-cli -c set "stock:{${PROBE_PRODUCT_B2}}" 0 >/dev/null

CHECKOUT_B_RAW=$(http_post "/api/v1/payments/checkout" \
    "{\"userId\":${USER_ID},\"orderedProductList\":[{\"productId\":${PROBE_PRODUCT_B1},\"quantity\":1},{\"productId\":${PROBE_PRODUCT_B2},\"quantity\":1}],\"gatewayType\":\"TOSS\"}")
CHECKOUT_B_STATUS="${CHECKOUT_B_RAW##*$'\n'}"
CHECKOUT_B_BODY="${CHECKOUT_B_RAW%$'\n'*}"

if [[ "${CHECKOUT_B_STATUS}" != "201" ]]; then
    print_error "❌ (5) checkout 실패 — status=${CHECKOUT_B_STATUS} body=${CHECKOUT_B_BODY}"
    PATH_FAILURES=$((PATH_FAILURES + 1))
else
    ORDER_B=$(extract_field "${CHECKOUT_B_BODY}" "orderId")
    AMOUNT_B=$(extract_field "${CHECKOUT_B_BODY}" "totalAmount")
    record_order_id "${ORDER_B}"

    CONFIRM_B_RAW=$(docker exec "${PS_CID}" curl -s -w '\n%{http_code}' -X POST "http://localhost:8080/api/v1/payments/confirm" \
        -H "Content-Type: application/json" \
        -d "{\"userId\":${USER_ID},\"orderId\":\"${ORDER_B}\",\"amount\":${AMOUNT_B},\"paymentKey\":\"cluster-check-key-b\",\"gatewayType\":\"TOSS\"}")
    CONFIRM_B_STATUS="${CONFIRM_B_RAW##*$'\n'}"

    if [[ "${CONFIRM_B_STATUS}" != "400" ]]; then
        print_error "❌ (5) confirm 기대 밖 결과 — status=${CONFIRM_B_STATUS} (기대 400 — 재고 부족 거절)"
        PATH_FAILURES=$((PATH_FAILURES + 1))
    else
        STOCK_B1_AFTER=$(docker exec "${CLUSTER_CID}" redis-cli -c get "stock:{${PROBE_PRODUCT_B1}}")
        if [[ "${STOCK_B1_AFTER}" == "${BENCH_STOCK}" ]]; then
            print_info "✅ (5) 거절 경로 통과 — orderId=${ORDER_B} confirm=400, stock:{${PROBE_PRODUCT_B1}}=${STOCK_B1_AFTER}(거절 전용 되돌리기로 원복 확인)"
        else
            print_error "❌ (5) 거절 전용 되돌리기 반영값 불일치 — stock:{${PROBE_PRODUCT_B1}}=${STOCK_B1_AFTER} (기대 ${BENCH_STOCK})"
            PATH_FAILURES=$((PATH_FAILURES + 1))
        fi
    fi
fi
echo ""

# ---------------------------------------------------------------------------
# 6. 격리 복구 조건부 보상 — 정상 흐름에서 안 타는 경로라 EVAL 로 직접 태운다.
#    qty=0 으로 호출해 실제 재고 수량은 건드리지 않는다.
# ---------------------------------------------------------------------------

print_section "▶ (6) 격리 복구 조건부 보상 — EVAL 직접 실행 (qty=0, 실 재고 불변)"

run_lua() {
    local file="$1" numkeys="$2"; shift 2
    local content
    content=$(cat "${LUA_DIR}/${file}")
    docker exec "${CLUSTER_CID}" redis-cli -c EVAL "${content}" "${numkeys}" "$@"
}

DECR_DONE_KEY="decrement:done:{${PROBE_PRODUCT_C}}:cluster-check-comp1"
COMP_DONE_KEY="compensation:done:{${PROBE_PRODUCT_C}}:cluster-check-comp1"
STOCK_C_KEY="stock:{${PROBE_PRODUCT_C}}"

# 선차감 토큰을 먼저 만든다 (조건부 보상이 확인할 흔적)
DECR_RESULT=$(run_lua "stock_decrement_atomic.lua" 2 "${DECR_DONE_KEY}" "${STOCK_C_KEY}" 0 691200)
COMP_OK_RESULT=$(run_lua "stock_compensation_if_decremented.lua" 3 "${DECR_DONE_KEY}" "${COMP_DONE_KEY}" "${STOCK_C_KEY}" 0 691200)
COMP_NO_TRACE_RESULT=$(run_lua "stock_compensation_if_decremented.lua" 3 \
    "decrement:done:{${PROBE_PRODUCT_C}}:cluster-check-comp2" \
    "compensation:done:{${PROBE_PRODUCT_C}}:cluster-check-comp2" \
    "${STOCK_C_KEY}" 0 691200)

if [[ "${DECR_RESULT}" == "OK" && "${COMP_OK_RESULT}" == "OK" && "${COMP_NO_TRACE_RESULT}" == "NO_DECREMENT" ]]; then
    print_info "✅ (6) 격리 복구 조건부 보상 통과 — 선차감 흔적 있음=OK / 없음=NO_DECREMENT"
else
    print_error "❌ (6) 격리 복구 조건부 보상 기대 밖 결과 — 선차감=${DECR_RESULT}(기대 OK) 흔적있음=${COMP_OK_RESULT}(기대 OK) 흔적없음=${COMP_NO_TRACE_RESULT}(기대 NO_DECREMENT)"
    PATH_FAILURES=$((PATH_FAILURES + 1))
fi
echo ""

# ---------------------------------------------------------------------------
# 7. 해시태그 슬롯 일치 + 노드별 분포 — 시드된 상품 전부
# ---------------------------------------------------------------------------

print_section "▶ (7) 해시태그 슬롯 일치 + 노드별 키 분포 — 상품 ${PRODUCT_COUNT}종"

SLOT_TABLE=$(docker exec "${CLUSTER_CID}" sh -c "
    for i in \$(seq 0 $((PRODUCT_COUNT - 1))); do
        id=\$((${PRODUCT_ID_BASE} + i))
        s1=\$(redis-cli cluster keyslot \"stock:{\${id}}\")
        s2=\$(redis-cli cluster keyslot \"decrement:done:{\${id}}:probe\")
        s3=\$(redis-cli cluster keyslot \"compensation:done:{\${id}}:probe\")
        echo \"\${id} \${s1} \${s2} \${s3}\"
    done
")

MISMATCH_TABLE=$(echo "${SLOT_TABLE}" | awk '$2 != $3 || $2 != $4 { print }')
MISMATCH_COUNT=0
if [[ -n "${MISMATCH_TABLE}" ]]; then
    MISMATCH_COUNT=$(echo "${MISMATCH_TABLE}" | grep -c '.')
fi

if [[ "${MISMATCH_COUNT}" -eq 0 ]]; then
    print_info "  ✅ 슬롯 일치 — ${PRODUCT_COUNT}종 전부 stock/선차감표시/되돌리기표시가 같은 슬롯"
else
    print_error "  ❌ 슬롯 불일치 ${MISMATCH_COUNT}종 (productId stock선차감되돌리기 순):"
    echo "${MISMATCH_TABLE}" | while read -r line; do echo "       ${line}"; done
fi

# 슬롯 → 소유 노드 매핑 (CLUSTER NODES 의 master 라인에서 슬롯 구간을 뽑는다)
RANGES=$(docker exec "${CLUSTER_CID}" redis-cli cluster nodes | awk '
    $3 ~ /master/ {
        split($2, addr, "@"); owner = addr[1]
        for (i = 9; i <= NF; i++) {
            if ($i ~ /^[0-9]+-[0-9]+$/) { split($i, r, "-"); print r[1], r[2], owner }
            else if ($i ~ /^[0-9]+$/) { print $i, $i, owner }
        }
    }
')

DISTRIBUTION=$(awk '
    NR==FNR { n++; s[n]=$1; e[n]=$2; o[n]=$3; next }
    {
        slot=$2; owner="UNKNOWN"
        for (i=1;i<=n;i++) { if (slot>=s[i] && slot<=e[i]) { owner=o[i]; break } }
        print owner
    }
' <(echo "${RANGES}") <(echo "${SLOT_TABLE}") | sort | uniq -c | awk '{print $2, $1}')

echo ""
echo "  노드별 상품 키 분포 (대상 ${PRODUCT_COUNT}종, 이상적 평균 $(awk -v c="${PRODUCT_COUNT}" -v n="${MASTER_COUNT}" 'BEGIN{printf "%.1f", c/n}')):"
IDEAL=$(awk -v c="${PRODUCT_COUNT}" -v n="${MASTER_COUNT}" 'BEGIN{print c/n}')
MAX_SKEW_PCT=0
while read -r owner count; do
    [[ -z "${owner}" ]] && continue
    skew_pct=$(awk -v c="${count}" -v i="${IDEAL}" 'BEGIN{d=c-i; if(d<0)d=-d; printf "%.1f", (d/i)*100}')
    echo "    ${owner}: ${count}개 (편차 ${skew_pct}%)"
    if (( $(awk -v s="${skew_pct}" -v m="${MAX_SKEW_PCT}" 'BEGIN{print (s>m)}') )); then
        MAX_SKEW_PCT="${skew_pct}"
    fi
done <<< "${DISTRIBUTION}"
echo ""

SKEW_EXCEEDED=false
if (( $(awk -v s="${MAX_SKEW_PCT}" -v t="${SKEW_THRESHOLD_PCT}" 'BEGIN{print (s>t)}') )); then
    SKEW_EXCEEDED=true
    print_error "  ❌ 노드별 분포 편차 ${MAX_SKEW_PCT}% 가 임계 ${SKEW_THRESHOLD_PCT}% 초과 — 상품 수를 늘려 다시 배치하는 것을 검토하세요"
else
    print_info "  ✅ 노드별 분포 편차 최대 ${MAX_SKEW_PCT}% — 임계 ${SKEW_THRESHOLD_PCT}% 이내"
fi
echo ""

# ---------------------------------------------------------------------------
# 최종 판정
# ---------------------------------------------------------------------------

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ 최종 판정 — masters=${MASTER_COUNT}"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  다섯 경로 실패: ${PATH_FAILURES}건"
echo "  슬롯 불일치:    ${MISMATCH_COUNT}종"
echo "  분포 최대 편차: ${MAX_SKEW_PCT}% (임계 ${SKEW_THRESHOLD_PCT}%)"
echo ""

EXIT_CODE=0
if [[ "${PATH_FAILURES}" -gt 0 ]]; then
    EXIT_CODE=2
elif [[ "${MISMATCH_COUNT}" -gt 0 ]]; then
    EXIT_CODE=3
elif [[ "${SKEW_EXCEEDED}" == "true" ]]; then
    EXIT_CODE=4
fi

if [[ "${EXIT_CODE}" -eq 0 ]]; then
    print_info "✅ bench-cluster-check 통과 — masters=${MASTER_COUNT}, 다섯 경로 정상 + 슬롯 일치 + 분포 정상"
else
    print_error "❌ bench-cluster-check 실패(exit ${EXIT_CODE}) — masters=${MASTER_COUNT}"
fi

exit "${EXIT_CODE}"
