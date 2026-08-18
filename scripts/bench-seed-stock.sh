#!/usr/bin/env bash
# bench-seed-stock.sh — 부하 측정 전용 대용량 재고 시드.
#
# 측정 중 재고 고갈을 방지하기 위해 product id=1 의 재고를 부하 총량보다
# 크게 설정한다. product RDB(SoT) → redis-stock 순서로 정합을 맞춘다.
#
# 멱등: 반복 실행 시 동일 값으로 덮어쓴다(SET/UPDATE).
# 운영 환경에서는 절대 사용하지 않는다.
#
# 사용법:
#   ./scripts/bench-seed-stock.sh
#   BENCH_STOCK=5000000 ./scripts/bench-seed-stock.sh
#
# 환경 변수:
#   BENCH_STOCK         — 설정할 재고 수량 (기본: 10000000)
#   PRODUCT_ID          — 측정 대상 product id (기본: 1)
#   MYSQL_PRODUCT_CONTAINER / DB / USER / PASSWORD — mysql-product 접속 정보
#   REDIS_STOCK_CONTAINER — redis-stock 컨테이너명
#
# 선행 조건:
#   - docker compose -f docker/docker-compose.infra.yml up -d 완료
#   - mysql-product / redis-stock 컨테이너 healthy
#
# 종료 코드:
#   0 — 시드 성공
#   1 — 접속 실패 또는 UPDATE 대상 없음

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=common.sh
source "${ROOT_DIR}/scripts/common.sh"

BENCH_STOCK="${BENCH_STOCK:-10000000}"
PRODUCT_ID="${PRODUCT_ID:-1}"

MYSQL_CONTAINER="${MYSQL_PRODUCT_CONTAINER:-payment-mysql-product}"
MYSQL_DB="${MYSQL_PRODUCT_DB:-product}"
MYSQL_USER="${MYSQL_PRODUCT_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PRODUCT_PASSWORD:-payment123}"

REDIS_CONTAINER="${REDIS_STOCK_CONTAINER:-payment-redis-stock}"
# 상품 번호를 Redis Cluster 해시태그({})로 감싼 키 — StockCacheRedisAdapter.KEY_PREFIX/KEY_SUFFIX 와 동일 규칙
REDIS_KEY="stock:{${PRODUCT_ID}}"

print_section "▶ bench-seed-stock 시작 — productId=${PRODUCT_ID}, stock=${BENCH_STOCK}"

# 1. product RDB stock.quantity UPDATE (SoT)
docker exec -i "${MYSQL_CONTAINER}" mysql \
    -u "${MYSQL_USER}" -p"${MYSQL_PASSWORD}" \
    -D "${MYSQL_DB}" -N -B -e \
    "UPDATE stock SET quantity = ${BENCH_STOCK} WHERE product_id = ${PRODUCT_ID};" \
    2>/dev/null

# UPDATE 반영 검증 — 멱등: 값이 이미 동일하면 MySQL affected rows=0 이므로
# ROW_COUNT 대신 실제 quantity 를 조회해 (행 존재 + 목표값 도달) 으로 판정한다.
RDB_VAL=$(docker exec -i "${MYSQL_CONTAINER}" mysql \
    -u "${MYSQL_USER}" -p"${MYSQL_PASSWORD}" \
    -D "${MYSQL_DB}" -N -B -e \
    "SELECT quantity FROM stock WHERE product_id = ${PRODUCT_ID};" \
    2>/dev/null | tail -1)

if [ -z "${RDB_VAL}" ]; then
    print_error "❌ product RDB stock 행 없음 — productId=${PRODUCT_ID} (접속 실패 또는 시드 누락)"
    exit 1
fi
if [ "${RDB_VAL}" != "${BENCH_STOCK}" ]; then
    print_error "❌ RDB UPDATE 반영 안 됨 — 현재값=${RDB_VAL}, 기대값=${BENCH_STOCK}"
    exit 1
fi

print_info "  RDB stock.quantity = ${BENCH_STOCK} (productId=${PRODUCT_ID})"

# 2. redis-stock SET (RDB→redis 정합)
docker exec -i "${REDIS_CONTAINER}" redis-cli SET "${REDIS_KEY}" "${BENCH_STOCK}" >/dev/null

print_info "  redis ${REDIS_KEY} = ${BENCH_STOCK}"

# 3. 정합 검증: redis GET 값이 RDB 와 일치하는지 확인
REDIS_VAL=$(docker exec -i "${REDIS_CONTAINER}" redis-cli GET "${REDIS_KEY}" 2>/dev/null)

if [ "${REDIS_VAL}" != "${BENCH_STOCK}" ]; then
    print_error "❌ 정합 불일치 — redis ${REDIS_KEY}=${REDIS_VAL}, 기대값=${BENCH_STOCK}"
    exit 1
fi

print_info "✅ bench-seed-stock 완료 — productId=${PRODUCT_ID}, stock=${BENCH_STOCK} (RDB=redis 정합 확인)"
