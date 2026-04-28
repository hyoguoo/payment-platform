#!/usr/bin/env bash
# seed-stock.sh — mysql-product 의 stock 테이블을 SoT 로 redis-stock 캐시 시드.
#
# 새 재고 모델: product RDB = SoT, redis-stock = payment-service 의 선차감 캐시.
# 부팅 직후 한 번만 동일 수치로 두 곳을 정렬하기 위해 이 스크립트를 실행한다.
# 이후 갱신은 payment 가 자기 책임 — 별도 동기화 스케줄러 없음.
#
# 멱등: 매번 SET 으로 덮어쓴다. 이미 차감된 redis 값이 있어도 product RDB 기준으로 재정렬됨 —
# 운영 중 호출은 위험할 수 있으니 부팅 직후 1회만 호출하는 것을 가정한다.
#
# 사용법:
#   ./scripts/seed-stock.sh
#   (compose-up.sh 가 자동 호출)
#
# 선행 조건:
#   - docker compose -f docker/docker-compose.infra.yml up -d 완료
#   - mysql-product / redis-stock 컨테이너 healthy
#
# 종료 코드:
#   0 — 시드 성공
#   1 — mysql/redis 접속 실패

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=common.sh
source "${ROOT_DIR}/scripts/common.sh"

MYSQL_CONTAINER="${MYSQL_PRODUCT_CONTAINER:-payment-mysql-product}"
MYSQL_DB="${MYSQL_PRODUCT_DB:-product}"
MYSQL_USER="${MYSQL_PRODUCT_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PRODUCT_PASSWORD:-payment123}"

REDIS_CONTAINER="${REDIS_STOCK_CONTAINER:-payment-redis-stock}"
REDIS_KEY_PREFIX="stock:"

print_section "▶ stock 시드 시작 — mysql-product → redis-stock"

# 1. mysql-product 에서 stock 테이블 SELECT
ROWS=$(docker exec -i "${MYSQL_CONTAINER}" mysql \
    -u "${MYSQL_USER}" -p"${MYSQL_PASSWORD}" \
    -D "${MYSQL_DB}" -N -B -e \
    "SELECT product_id, quantity FROM stock;" 2>/dev/null)

if [ -z "${ROWS}" ]; then
    print_warning "stock 테이블이 비어있거나 접속 실패 — 시드 skip"
    exit 0
fi

# 2. 각 row 를 redis-stock 에 SET
COUNT=0
while IFS=$'\t' read -r PRODUCT_ID QUANTITY; do
    if [ -z "${PRODUCT_ID}" ]; then
        continue
    fi
    docker exec -i "${REDIS_CONTAINER}" redis-cli SET \
        "${REDIS_KEY_PREFIX}${PRODUCT_ID}" "${QUANTITY}" >/dev/null
    COUNT=$((COUNT + 1))
done <<< "${ROWS}"

print_info "✅ ${COUNT} productId 시드 완료 (${MYSQL_CONTAINER} → ${REDIS_CONTAINER})"
