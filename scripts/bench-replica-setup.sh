#!/usr/bin/env bash
# bench-replica-setup.sh — payment DB 비동기 읽기 복제본 1대 구성.
#
# mysql-payment(소스) → mysql-payment-replica(복제본) 로 복제 계정을 만들고,
# 초기 스냅샷(mysqldump --single-transaction)을 복제본에 넣은 뒤 바이너리 로그
# 좌표로 복제를 시작한다. 이미 복제 중이면 재동기화를 건너뛰고 왕복 확인만 한다.
#
# 사용법:
#   docker compose -f docker/docker-compose.infra.yml \
#     -f docker/docker-compose.apps.yml \
#     -f docker/docker-compose.scaleout.yml up -d mysql-payment mysql-payment-replica
#   ./scripts/bench-replica-setup.sh
#
# 환경 변수:
#   MYSQL_PAYMENT_CONTAINER / MYSQL_PAYMENT_REPLICA_CONTAINER — 컨테이너명
#   MYSQL_PAYMENT_ROOT_PASSWORD — root 비밀번호 (docker-compose.infra.yml 과 동일 기본값)
#   MYSQL_PAYMENT_REPL_USER / MYSQL_PAYMENT_REPL_PASSWORD — 복제 전용 계정
#
# 멱등: 복제본이 이미 정상 복제 중이면 초기 동기화를 건너뛰고 왕복 확인만 재실행한다.
#
# 종료 코드:
#   0 — 복제 정상 (IO/SQL 스레드 Yes) + 왕복 확인 성공
#   1 — 접속 실패, 초기 동기화 실패, 복제 스레드가 시간 내 Yes 로 안 됨, 왕복 확인 실패

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=common.sh
source "${ROOT_DIR}/scripts/common.sh"

SOURCE_CONTAINER="${MYSQL_PAYMENT_CONTAINER:-payment-mysql-payment}"
REPLICA_CONTAINER="${MYSQL_PAYMENT_REPLICA_CONTAINER:-payment-mysql-payment-replica}"
ROOT_PASSWORD="${MYSQL_PAYMENT_ROOT_PASSWORD:-payment123}"
REPL_USER="${MYSQL_PAYMENT_REPL_USER:-repl}"
REPL_PASSWORD="${MYSQL_PAYMENT_REPL_PASSWORD:-payment123repl}"
DB_NAME="payment-platform"
SOURCE_HOST_IN_NETWORK="mysql-payment"
SOURCE_PORT=3306

DUMP_FILE="$(mktemp /tmp/bench-replica-seed.XXXXXX.sql)"
trap 'rm -f "${DUMP_FILE}"' EXIT

mysql_source() {
    docker exec -i "${SOURCE_CONTAINER}" mysql -u root -p"${ROOT_PASSWORD}" "$@"
}

mysql_replica() {
    docker exec -i "${REPLICA_CONTAINER}" mysql -u root -p"${ROOT_PASSWORD}" "$@"
}

wait_for_ping() {
    local container="$1"
    local attempt=0
    local max_attempts=30
    while [ "${attempt}" -lt "${max_attempts}" ]; do
        if docker exec "${container}" mysqladmin ping -h localhost --silent >/dev/null 2>&1; then
            return 0
        fi
        attempt=$((attempt + 1))
        sleep 2
    done
    return 1
}

replica_thread_status() {
    # "Replica_IO_Running:Yes Replica_SQL_Running:Yes" 형식으로 두 값을 함께 출력
    mysql_replica -e "SHOW REPLICA STATUS\G" 2>/dev/null \
        | awk '/Replica_IO_Running:/ {io=$2} /Replica_SQL_Running:/ {sql=$2} END {print io" "sql}'
}

print_section "▶ bench-replica-setup 시작 — ${SOURCE_CONTAINER} → ${REPLICA_CONTAINER}"

if ! wait_for_ping "${SOURCE_CONTAINER}"; then
    print_error "❌ ${SOURCE_CONTAINER} 접속 실패 (ping 시간 초과)"
    exit 1
fi
if ! wait_for_ping "${REPLICA_CONTAINER}"; then
    print_error "❌ ${REPLICA_CONTAINER} 접속 실패 (ping 시간 초과)"
    exit 1
fi
print_info "  두 컨테이너 ping 확인 완료"

# 1. 복제 계정 준비 (소스, 멱등)
mysql_source <<SQL
CREATE USER IF NOT EXISTS '${REPL_USER}'@'%' IDENTIFIED WITH mysql_native_password BY '${REPL_PASSWORD}';
GRANT REPLICATION SLAVE ON *.* TO '${REPL_USER}'@'%';
FLUSH PRIVILEGES;
SQL
if [ $? -ne 0 ]; then
    print_error "❌ 복제 계정 생성 실패"
    exit 1
fi
print_info "  복제 계정 '${REPL_USER}'@'%' 준비 완료"

# 2. 이미 정상 복제 중이면 초기 동기화를 건너뛴다 (멱등)
read -r CURRENT_IO CURRENT_SQL <<<"$(replica_thread_status)"
if [ "${CURRENT_IO}" = "Yes" ] && [ "${CURRENT_SQL}" = "Yes" ]; then
    print_info "  이미 정상 복제 중 (IO=${CURRENT_IO}, SQL=${CURRENT_SQL}) — 초기 동기화 생략"
else
    print_section "  초기 동기화 시작 (mysqldump --single-transaction)"

    docker exec "${SOURCE_CONTAINER}" mysqldump -u root -p"${ROOT_PASSWORD}" \
        --single-transaction --source-data=2 --databases "${DB_NAME}" \
        > "${DUMP_FILE}" 2>/dev/null
    if [ ! -s "${DUMP_FILE}" ]; then
        print_error "❌ mysqldump 실패 또는 빈 결과"
        exit 1
    fi

    POS_LINE=$(grep -m1 -iE "CHANGE (MASTER TO|REPLICATION SOURCE TO)" "${DUMP_FILE}")
    if [ -z "${POS_LINE}" ]; then
        print_error "❌ 덤프에서 binlog 좌표(CHANGE MASTER/REPLICATION 문)를 찾지 못함"
        exit 1
    fi
    LOG_FILE=$(echo "${POS_LINE}" | grep -oE "(MASTER_LOG_FILE|SOURCE_LOG_FILE)='[^']*'" | sed -E "s/.*='([^']*)'/\1/")
    LOG_POS=$(echo "${POS_LINE}" | grep -oE "(MASTER_LOG_POS|SOURCE_LOG_POS)=[0-9]+" | grep -oE "[0-9]+")
    if [ -z "${LOG_FILE}" ] || [ -z "${LOG_POS}" ]; then
        print_error "❌ binlog 좌표 파싱 실패 — line: ${POS_LINE}"
        exit 1
    fi
    print_info "  binlog 좌표 확인 — FILE=${LOG_FILE}, POS=${LOG_POS}"

    if ! mysql_replica < "${DUMP_FILE}"; then
        print_error "❌ 복제본으로 스냅샷 복원 실패"
        exit 1
    fi
    print_info "  스냅샷 복원 완료"

    mysql_replica <<SQL
STOP REPLICA;
RESET REPLICA ALL;
CHANGE REPLICATION SOURCE TO
    SOURCE_HOST='${SOURCE_HOST_IN_NETWORK}',
    SOURCE_PORT=${SOURCE_PORT},
    SOURCE_USER='${REPL_USER}',
    SOURCE_PASSWORD='${REPL_PASSWORD}',
    SOURCE_LOG_FILE='${LOG_FILE}',
    SOURCE_LOG_POS=${LOG_POS};
START REPLICA;
SQL
    if [ $? -ne 0 ]; then
        print_error "❌ CHANGE REPLICATION SOURCE / START REPLICA 실패"
        exit 1
    fi

    # 3. IO/SQL 스레드가 둘 다 Yes 가 될 때까지 대기
    attempt=0
    max_attempts=15
    while [ "${attempt}" -lt "${max_attempts}" ]; do
        read -r CURRENT_IO CURRENT_SQL <<<"$(replica_thread_status)"
        if [ "${CURRENT_IO}" = "Yes" ] && [ "${CURRENT_SQL}" = "Yes" ]; then
            break
        fi
        attempt=$((attempt + 1))
        sleep 2
    done

    if [ "${CURRENT_IO}" != "Yes" ] || [ "${CURRENT_SQL}" != "Yes" ]; then
        print_error "❌ 복제 스레드가 시간 내 정상화되지 않음 — IO=${CURRENT_IO}, SQL=${CURRENT_SQL}"
        mysql_replica -e "SHOW REPLICA STATUS\G" 2>/dev/null | grep -E "Last_(IO|SQL)_Error"
        exit 1
    fi
fi

print_info "  복제 스레드 정상 — Replica_IO_Running=Yes, Replica_SQL_Running=Yes"

# 4. 왕복 확인 — 소스에 넣은 값이 복제본에서 읽히는지 확인 (매 실행마다 새 값)
PROBE_VALUE="probe-$(date +%s)-$$"
mysql_source <<SQL
CREATE TABLE IF NOT EXISTS \`${DB_NAME}\`.\`bench_replica_probe\` (
    id INT PRIMARY KEY,
    value VARCHAR(64) NOT NULL
);
INSERT INTO \`${DB_NAME}\`.\`bench_replica_probe\` (id, value) VALUES (1, '${PROBE_VALUE}')
    ON DUPLICATE KEY UPDATE value = '${PROBE_VALUE}';
SQL
if [ $? -ne 0 ]; then
    print_error "❌ 왕복 확인용 소스 행 기록 실패"
    exit 1
fi

attempt=0
max_attempts=15
REPLICATED_VALUE=""
while [ "${attempt}" -lt "${max_attempts}" ]; do
    REPLICATED_VALUE=$(mysql_replica -N -B \
        -e "SELECT value FROM \`${DB_NAME}\`.\`bench_replica_probe\` WHERE id = 1;" 2>/dev/null)
    if [ "${REPLICATED_VALUE}" = "${PROBE_VALUE}" ]; then
        break
    fi
    attempt=$((attempt + 1))
    sleep 1
done

if [ "${REPLICATED_VALUE}" != "${PROBE_VALUE}" ]; then
    print_error "❌ 왕복 확인 실패 — 소스=${PROBE_VALUE}, 복제본=${REPLICATED_VALUE:-<없음>}"
    exit 1
fi

print_info "✅ bench-replica-setup 완료 — 복제 정상 + 왕복 확인 성공 (value=${PROBE_VALUE})"
