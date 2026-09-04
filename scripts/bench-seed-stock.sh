#!/usr/bin/env bash
# bench-seed-stock.sh — 부하 측정 전용 다품종 재고 시드.
#
# PRODUCT_COUNT(기본 100)개 상품을 준비하고, 상품별 재고를 원본(product RDB, SoT)과
# 캐시(redis-stock)에 같은 상수(BENCH_STOCK)로 맞춘다.
#
# 상품 id 범위: PRODUCT_ID_BASE .. PRODUCT_ID_BASE+PRODUCT_COUNT-1 (기본 1000..1099).
# 스모크/통합 테스트가 함께 쓰는 product id=1 (V2__seed_product_stock.sql)과 겹치지
# 않도록 기본값을 충분히 띄워 뒀다 — 그 자리는 건드리지 않는다.
#
# 재고 캐시가 클러스터로 뜬 구성(redis-stock-cluster)에서는 상품마다 슬롯 소유
# 노드가 달라진다. redis-cli를 클러스터 모드(-c)로 불러 MOVED 리다이렉트를 따라가게
# 한다 — 파이프 모드(--pipe)는 응답을 처리하지 않아 리다이렉트를 못 따라간다.
#
# 멱등: product/stock 행은 INSERT IGNORE로 보장하고, 재고 수량은 매번 BENCH_STOCK으로
# UPDATE/SET해 덮어쓴다.
# 운영 환경에서는 절대 사용하지 않는다.
#
# 사용법:
#   ./scripts/bench-seed-stock.sh
#   PRODUCT_COUNT=50 BENCH_STOCK=5000000 ./scripts/bench-seed-stock.sh
#
# 환경 변수:
#   PRODUCT_COUNT         — 시드할 상품 종류 수 (기본: 100)
#   PRODUCT_ID_BASE        — 시작 상품 id (기본: 1000)
#   BENCH_STOCK            — 상품별로 설정할 재고 수량 (기본: 10000000)
#   MYSQL_PRODUCT_CONTAINER / DB / USER / PASSWORD — mysql-product 접속 정보
#   REDIS_STOCK_CONTAINER  — redis-stock(-cluster) 컨테이너명. 클러스터 구성이면
#                            마스터 노드 하나만 지정해도 -c가 리다이렉트를 따라간다.
#
# 선행 조건:
#   - docker compose -f docker/docker-compose.infra.yml up -d 완료
#   - mysql-product / redis-stock(-cluster) 컨테이너 healthy
#
# 종료 코드:
#   0 — 시드 성공, 상품 전부 원본=캐시 정합 확인
#   1 — 인자 오류, 접속 실패, 또는 반영/정합 불일치

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=common.sh
source "${ROOT_DIR}/scripts/common.sh"

PRODUCT_COUNT="${PRODUCT_COUNT:-100}"
PRODUCT_ID_BASE="${PRODUCT_ID_BASE:-1000}"
BENCH_STOCK="${BENCH_STOCK:-10000000}"

MYSQL_CONTAINER="${MYSQL_PRODUCT_CONTAINER:-payment-mysql-product}"
MYSQL_DB="${MYSQL_PRODUCT_DB:-product}"
MYSQL_USER="${MYSQL_PRODUCT_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PRODUCT_PASSWORD:-payment123}"

REDIS_CONTAINER="${REDIS_STOCK_CONTAINER:-payment-redis-stock}"

if ! [[ "${PRODUCT_COUNT}" =~ ^[0-9]+$ ]] || [[ "${PRODUCT_COUNT}" -lt 1 ]]; then
    print_error "❌ PRODUCT_COUNT 는 1 이상의 정수여야 한다 (입력: '${PRODUCT_COUNT}')"
    exit 1
fi
if ! [[ "${PRODUCT_ID_BASE}" =~ ^[0-9]+$ ]]; then
    print_error "❌ PRODUCT_ID_BASE 는 정수여야 한다 (입력: '${PRODUCT_ID_BASE}')"
    exit 1
fi

LAST_ID=$((PRODUCT_ID_BASE + PRODUCT_COUNT - 1))

print_section "▶ bench-seed-stock 시작 — productId=${PRODUCT_ID_BASE}..${LAST_ID} (${PRODUCT_COUNT}종), stock=${BENCH_STOCK}"

# 1. product/stock 행 보장 + 재고를 상수로 UPDATE (단일 접속·단일 트랜잭션으로 한 번에)
SQL="START TRANSACTION;"
for ((i = 0; i < PRODUCT_COUNT; i++)); do
    id=$((PRODUCT_ID_BASE + i))
    SQL+="INSERT IGNORE INTO product (id, name, price, description, seller_id) VALUES (${id}, 'Bench Product ${id}', 1000.00, 'bench seed', 1);"
    SQL+="INSERT IGNORE INTO stock (product_id, quantity) VALUES (${id}, ${BENCH_STOCK});"
    SQL+="UPDATE stock SET quantity = ${BENCH_STOCK} WHERE product_id = ${id};"
done
SQL+="COMMIT;"

if ! docker exec -i "${MYSQL_CONTAINER}" mysql \
    -u "${MYSQL_USER}" -p"${MYSQL_PASSWORD}" \
    -D "${MYSQL_DB}" -N -B -e "${SQL}" \
    2>/dev/null; then
    print_error "❌ mysql-product 접속 또는 시드 SQL 실패 (컨테이너: ${MYSQL_CONTAINER})"
    exit 1
fi

# 2. RDB 반영 검증 — 범위 내에서 quantity=BENCH_STOCK 인 행 수가 상품 종수와 같은지 확인
RDB_OK_COUNT=$(docker exec -i "${MYSQL_CONTAINER}" mysql \
    -u "${MYSQL_USER}" -p"${MYSQL_PASSWORD}" \
    -D "${MYSQL_DB}" -N -B -e \
    "SELECT COUNT(*) FROM stock WHERE product_id BETWEEN ${PRODUCT_ID_BASE} AND ${LAST_ID} AND quantity = ${BENCH_STOCK};" \
    2>/dev/null | tail -1)

if [ "${RDB_OK_COUNT:-0}" != "${PRODUCT_COUNT}" ]; then
    print_error "❌ RDB 반영 불완전 — 목표 ${PRODUCT_COUNT}종 중 ${RDB_OK_COUNT:-0}종만 stock=${BENCH_STOCK}"
    exit 1
fi

print_info "  RDB stock.quantity = ${BENCH_STOCK} × ${PRODUCT_COUNT}종 (productId=${PRODUCT_ID_BASE}..${LAST_ID})"

# 3. redis-stock SET + 정합 확인을 상품마다 반복 — 컨테이너 안에서 한 번의 exec로 처리해
#    호스트→컨테이너 프로세스 기동 비용을 100회가 아닌 1회로 줄인다.
#    실패한 productId=value 쌍을 stdout에 출력하고, 실패 건수를 그대로 종료 코드로 반환한다.
SET_MISMATCH=$(docker exec -i "${REDIS_CONTAINER}" sh -c "
    fail=0
    for i in \$(seq 0 $((PRODUCT_COUNT - 1))); do
        id=\$((${PRODUCT_ID_BASE} + i))
        redis-cli -c SET \"stock:{\${id}}\" \"${BENCH_STOCK}\" >/dev/null
        val=\$(redis-cli -c GET \"stock:{\${id}}\")
        if [ \"\${val}\" != \"${BENCH_STOCK}\" ]; then
            echo \"productId=\${id} redis=\${val}\"
            fail=\$((fail + 1))
        fi
    done
    exit \${fail}
")
SET_EXIT=$?

if [ "${SET_EXIT}" -ne 0 ]; then
    print_error "❌ redis 정합 불일치 ${SET_EXIT}건 (기대값=${BENCH_STOCK}):"
    echo "${SET_MISMATCH}"
    exit 1
fi

print_info "  redis stock:{${PRODUCT_ID_BASE}..${LAST_ID}} = ${BENCH_STOCK}"
print_info "✅ bench-seed-stock 완료 — ${PRODUCT_COUNT}종 전부 RDB=redis 정합 확인 (productId=${PRODUCT_ID_BASE}..${LAST_ID}, stock=${BENCH_STOCK})"
