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
# 재고 키 형식을 바꿀 때(예: 상품 기준 해시태그 도입)의 전환 절차:
#   이 스크립트는 매번 새 형식으로 SET 하므로 그것만으로도 새 키는 심긴다. 하지만 옛 스택을
#   그대로 두면 redis-stock 볼륨에 옛 형식 키가 죽은 채로 남고, 무엇보다 진행 중이던 결제·격리
#   건이 옛 캐시 상태를 전제로 남아 있어 in-flight 여부를 사람이 확인해야 한다. 로컬 학습
#   환경이라 데이터를 지킬 이유가 없으므로, 캐시와 DB 볼륨을 모두 비우고 새로 띄워
#   진행 중 결제가 0 인 상태에서 시작한다:
#     bash scripts/compose-up.sh --clean   # 전체 종료 + redis-stock/mysql-* 볼륨 전부 제거
#     bash scripts/compose-up.sh           # 재기동 — Flyway 처음부터, 이 스크립트가 새 키로 시드
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
# 상품 번호를 Redis Cluster 해시태그({})로 감싼 키 — StockCacheRedisAdapter.KEY_PREFIX/KEY_SUFFIX 와 동일 규칙
REDIS_KEY_PREFIX="stock:{"
REDIS_KEY_SUFFIX="}"

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
    # -i 를 쓰지 않는다 — 루프 안에서 stdin 을 열면 herestring 의 남은 줄을 먹어
    # 첫 상품만 시드되고 나머지가 조용히 누락된다.
    docker exec "${REDIS_CONTAINER}" redis-cli SET \
        "${REDIS_KEY_PREFIX}${PRODUCT_ID}${REDIS_KEY_SUFFIX}" "${QUANTITY}" >/dev/null
    COUNT=$((COUNT + 1))
done <<< "${ROWS}"

print_info "✅ ${COUNT} productId 시드 완료 (${MYSQL_CONTAINER} → ${REDIS_CONTAINER})"
