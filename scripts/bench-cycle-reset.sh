#!/usr/bin/env bash
# bench-cycle-reset.sh — 사이클 사이 재구성 절차. 다섯 단계를 순서대로 밟아야만 캐시를 비운다.
#
# 확인과 비우기 사이가 열려 있으면 낙오 확정의 캐시 차감분이 지워져 같은 재고 단위가
# 다음 사이클에서 또 팔린다 — 원본은 양수라 음수 가드에도 안 걸리는 조용한 초과 판매다.
# 이 스크립트의 존재 이유는 그 창을 최대한 좁히고, 좁혀지지 않으면 비우지 않고 실패하는 것이다.
#
# 다섯 단계:
#   (1) 부하 도구가 멈춘 것을 확인한다 — 이 환경에서 확정 요청의 유일한 출처
#   (2) 미종결 결제(READY/IN_PROGRESS/RETRYING) 0, 격리 결제(QUARANTINED) 0,
#       미회수 선차감 기록(stock_hold_record.status=NOISE) 0 을 짧은 폴링 창으로 안정 확인.
#       미종결과 격리는 이 프로젝트의 기존 검증 용어(scripts/k6/verify-settlement.sh)에서
#       별개로 세는 값이다 — 미종결만 보면 격리 잔류가 그대로 통과해 격리가 남은 채로
#       캐시를 비우게 된다. 격리 잔류는 관리자 종결(POST .../resolve-quarantine)로 자동
#       시도한 뒤 재확인한다 — 벤더 승인이 확인된 건은 종결이 거부되고, 그 경우도 포함해
#       반복해도 안 비면 무기한 대기하지 않고 사이클을 실패 처리한다.
#   (3) 재고 확정 메시지의 소비 적체(consumer group product-service-stock-commit) 0 안정 확인
#   (4) 회수 주기 작업 정지 — payment-service 를 서비스 단위로 멈춘다(docker compose stop).
#       회수 워커(StockHoldRecoveryWorker)는 인스턴스마다 독립으로 돌고 끄는 설정값이 없어,
#       컨테이너 하나만 겨냥하면 인스턴스 여러 대 구간에서 남은 인스턴스의 회수가 재확인과
#       비우기 사이에 끼어든다
#   (5) (2)와 (3)을 한 번 더 즉시 재확인한 직후에만 캐시를 비우고 상품별 상수로 재시드
#       (scripts/bench-seed-stock.sh 위임 — 원본과 캐시를 같은 값으로 함께 덮는다)
#
# 사용법:
#   ./scripts/bench-cycle-reset.sh
#   MAX_POLL_ATTEMPTS=60 ./scripts/bench-cycle-reset.sh   # 인스턴스 대수가 많아 배출이 느릴 때
#
# 환경 변수:
#   STABLE_POLL_INTERVAL_SECONDS — (2)/(3) 안정 확인 폴링 간격 초 (기본 3)
#   STABLE_REQUIRED_READS        — (2)/(3) 을 안정으로 판정할 연속 0 회수 (기본 3)
#   MAX_POLL_ATTEMPTS            — (2)/(3) 각 단계 최대 폴링 시도 (기본 40 — 최대 약 120초)
#   QUARANTINE_RESOLVE_REASON    — 격리 자동 종결 시 감사 사유 (기본 "bench-cycle-reset 자동 회수")
#   MYSQL_PAYMENT_CONTAINER / DB / USER / PASSWORD — mysql-payment 접속 정보
#   KAFKA_CONTAINER               — kafka 컨테이너명 (기본 payment-kafka)
#   STOCK_COMMIT_GROUP            — 재고 확정 소비자 그룹 (기본 product-service-stock-commit)
#   PRODUCT_COUNT / PRODUCT_ID_BASE / BENCH_STOCK / MYSQL_PRODUCT_* / REDIS_STOCK_CONTAINER
#     — (5) 재시드에 그대로 전달(scripts/bench-seed-stock.sh 환경 변수와 동일, 미지정 시 그 기본값)
#
# 선행 조건:
#   docker compose -f docker/docker-compose.infra.yml -f docker/docker-compose.apps.yml \
#     -f docker/docker-compose.scaleout.yml up -d 로 스택이 떠 있다
#
# 종료 코드:
#   0 — 다섯 단계 전부 통과, 캐시 비우기 + 재시드 완료
#   1 — 선결 조건 미충족(부하 도구 실행 중, 컨테이너 접속 실패 등)
#   2 — (2)/(3) 안정 확인 실패(잔류가 안 비거나 관리자 종결로도 안 풀림) — 캐시를 비우지 않고 종료
#   3 — (4) payment-service 정지 실패 — 캐시를 비우지 않고 종료
#   4 — (5) 재확인 실패 또는 재시드 실패 — 이 경우 캐시가 이미 열린 창일 수 있어 즉시 사람 개입 필요

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=common.sh
source "${ROOT_DIR}/scripts/common.sh"

STABLE_POLL_INTERVAL_SECONDS="${STABLE_POLL_INTERVAL_SECONDS:-3}"
STABLE_REQUIRED_READS="${STABLE_REQUIRED_READS:-3}"
MAX_POLL_ATTEMPTS="${MAX_POLL_ATTEMPTS:-40}"
QUARANTINE_RESOLVE_REASON="${QUARANTINE_RESOLVE_REASON:-bench-cycle-reset 자동 회수}"

MYSQL_CONTAINER="${MYSQL_PAYMENT_CONTAINER:-payment-mysql-payment}"
MYSQL_DB="${MYSQL_PAYMENT_DB:-payment-platform}"
MYSQL_USER="${MYSQL_PAYMENT_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PAYMENT_PASSWORD:-payment123}"

KAFKA_CONTAINER="${KAFKA_CONTAINER:-payment-kafka}"
STOCK_COMMIT_GROUP="${STOCK_COMMIT_GROUP:-product-service-stock-commit}"

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

check_docker

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ bench-cycle-reset — 사이클 재구성 다섯 단계"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# ---------------------------------------------------------------------------
# (1) 부하 도구가 멈춘 것을 확인한다 — 이 환경에서 확정 요청의 유일한 출처
# ---------------------------------------------------------------------------

print_section "▶ (1) 부하 도구 정지 확인"

if pgrep -f "k6 run" > /dev/null 2>&1; then
    print_error "❌ (1) k6 프로세스가 아직 실행 중이다 — 먼저 부하 도구를 멈춘 뒤 재실행하세요"
    exit 1
fi

RUNNING_K6=$(docker ps --format '{{.Names}}\t{{.Image}}' 2> /dev/null | grep -i 'k6' || true)
if [[ -n "${RUNNING_K6}" ]]; then
    print_error "❌ (1) k6 컨테이너가 아직 실행 중이다:"
    echo "${RUNNING_K6}"
    exit 1
fi

print_info "✅ (1) 부하 도구 정지 확인 — k6 프로세스/컨테이너 없음"
echo ""

# ---------------------------------------------------------------------------
# (2) 미종결 0, 격리 0, 미회수 선차감 기록 0 — 짧은 폴링 창으로 안정 확인
# ---------------------------------------------------------------------------

count_unsettled() {
    # READY(접수) / IN_PROGRESS(진행 중) / RETRYING(재시도 대기, 현재 스키마에는 값이 없어
    # 항상 0 기여 — verify-settlement.sh 의 미종결 정의와 동일하게 맞춰 둔다)
    mysql_query "SELECT COUNT(*) FROM payment_event WHERE status IN ('READY','IN_PROGRESS','RETRYING');" | tail -1
}

count_quarantined() {
    mysql_query "SELECT COUNT(*) FROM payment_event WHERE status = 'QUARANTINED';" | tail -1
}

count_noise() {
    mysql_query "SELECT COUNT(*) FROM stock_hold_record WHERE status = 'NOISE';" | tail -1
}

# 격리 결제를 관리자 종결(안전 종결/FAILED)로 자동 시도한다. payment-service 살아있는
# 인스턴스 하나에 exec 로 들어가 admin 컨트롤러(POST /admin/payments/events/{id}/resolve-quarantine)
# 를 로컬로 호출한다 — 이 컨트롤러는 gateway 라우팅 대상이 아니라 컨테이너 내부에서만 닿는다.
# 벤더 승인이 확인된 건은 유스케이스가 거부한다(과금이 살아있는 건이라 되돌릴 수 없다) —
# 그 경우 이 함수는 진행 상황만 출력하고, 남은 잔류는 상위 안정 확인 루프가 재시도 횟수로 가른다.
resolve_quarantined_events() {
    local cid
    cid=$(dc ps -q payment-service 2> /dev/null | head -n1)
    if [[ -z "${cid}" ]]; then
        print_warning "  ⚠️  격리 자동 종결 스킵 — 살아있는 payment-service 컨테이너 없음"
        return
    fi

    local rows
    rows=$(mysql_query "SELECT id, order_id FROM payment_event WHERE status = 'QUARANTINED';")
    if [[ -z "${rows}" ]]; then
        return
    fi

    while IFS=$'\t' read -r event_id order_id; do
        [[ -z "${event_id}" ]] && continue
        local location
        location=$(docker exec "${cid}" curl -s -o /dev/null -D - -X POST \
            "http://localhost:8080/admin/payments/events/${event_id}/resolve-quarantine" \
            --data-urlencode "orderId=${order_id}" \
            --data-urlencode "reason=${QUARANTINE_RESOLVE_REASON}" 2> /dev/null \
            | grep -i '^location:' | tr -d '\r')
        if echo "${location}" | grep -q "error"; then
            print_warning "  ⚠️  격리 종결 거부됨 — orderId=${order_id} (벤더 승인 확인 등 사유는 상세 화면 flash 메시지 참고)"
        else
            print_info "  ↻ 격리 종결 시도 — orderId=${order_id} (eventId=${event_id})"
        fi
    done <<< "${rows}"
}

# 공용 안정 확인 루프 — check_fn 이 연속 STABLE_REQUIRED_READS 회 0(성공, echo 0)을 돌려줄 때까지
# MAX_POLL_ATTEMPTS 회까지 폴링한다. 실패 시 1을 반환하고 호출부가 exit 코드를 정한다.
wait_stable() {
    local label="$1"
    local check_fn="$2"
    local stable=0
    local attempt=0

    while true; do
        attempt=$((attempt + 1))
        if "${check_fn}"; then
            stable=$((stable + 1))
        else
            stable=0
        fi

        if [[ "${stable}" -ge "${STABLE_REQUIRED_READS}" ]]; then
            print_info "✅ ${label} — 연속 ${STABLE_REQUIRED_READS}회 0 확인"
            return 0
        fi

        if [[ "${attempt}" -ge "${MAX_POLL_ATTEMPTS}" ]]; then
            print_error "❌ ${label} — ${MAX_POLL_ATTEMPTS}회 폴링에도 안정화되지 않음"
            return 1
        fi

        sleep "${STABLE_POLL_INTERVAL_SECONDS}"
    done
}

check_step2() {
    local unsettled quarantined noise
    unsettled=$(count_unsettled)
    quarantined=$(count_quarantined)
    noise=$(count_noise)
    unsettled="${unsettled:-0}"
    quarantined="${quarantined:-0}"
    noise="${noise:-0}"

    if [[ "${unsettled}" -eq 0 && "${quarantined}" -eq 0 && "${noise}" -eq 0 ]]; then
        return 0
    fi

    echo "    잔류 — 미종결=${unsettled} 격리=${quarantined} 미회수 선차감 기록=${noise}"
    if [[ "${quarantined}" -gt 0 ]]; then
        resolve_quarantined_events
    fi
    return 1
}

print_section "▶ (2) 미종결 0 / 격리 0 / 미회수 선차감 기록 0 — 안정 확인"
if ! wait_stable "(2) 미종결·격리·미회수" check_step2; then
    print_error "❌ (2) 재구성 중단 — 캐시를 비우지 않는다"
    echo "    최종 상태 — 미종결=$(count_unsettled) 격리=$(count_quarantined) 미회수 선차감 기록=$(count_noise)"
    exit 2
fi
echo ""

# ---------------------------------------------------------------------------
# (3) 재고 확정 메시지의 소비 적체 0 안정 확인 — consumer group product-service-stock-commit
# ---------------------------------------------------------------------------

kafka_lag_sum() {
    local out
    out=$(docker exec "${KAFKA_CONTAINER}" kafka-consumer-groups \
        --bootstrap-server localhost:9092 --describe --group "${STOCK_COMMIT_GROUP}" 2>/dev/null)
    if [[ -z "${out}" ]]; then
        echo "ERROR"
        return
    fi
    echo "${out}" | awk '
        $1 == "GROUP" { next }
        NF >= 6 && $6 ~ /^[0-9]+$/ { sum += $6; seen = 1 }
        END { if (seen) { print sum } else { print 0 } }
    '
}

check_step3() {
    local lag
    lag=$(kafka_lag_sum)
    if [[ "${lag}" == "ERROR" ]]; then
        echo "    소비 적체 조회 실패 — 컨테이너(${KAFKA_CONTAINER}) 또는 그룹(${STOCK_COMMIT_GROUP}) 확인 필요"
        return 1
    fi
    if [[ "${lag}" -eq 0 ]]; then
        return 0
    fi
    echo "    잔류 — 소비 적체=${lag}"
    return 1
}

print_section "▶ (3) 재고 확정 메시지 소비 적체 0 — 안정 확인 (group=${STOCK_COMMIT_GROUP})"
if ! wait_stable "(3) 소비 적체" check_step3; then
    print_error "❌ (3) 재구성 중단 — 캐시를 비우지 않는다"
    exit 2
fi
echo ""

# ---------------------------------------------------------------------------
# (4) 회수 주기 작업 정지 — payment 를 서비스 단위로 멈춘다
# ---------------------------------------------------------------------------

print_section "▶ (4) 회수 주기 작업 정지 — payment-service 서비스 단위 정지"
print_section "  (StockHoldRecoveryWorker 는 인스턴스마다 독립으로 돌고 끄는 설정값이 없다 —"
print_section "   컨테이너 하나만 겨냥하면 남은 인스턴스의 회수가 재확인과 비우기 사이에 끼어든다)"

if ! dc stop payment-service > /dev/null 2>&1; then
    print_error "❌ (4) payment-service 정지 실패"
    exit 3
fi

RUNNING_AFTER_STOP=$(dc ps -q --status running payment-service 2> /dev/null | wc -l | tr -d ' ')
if [[ "${RUNNING_AFTER_STOP}" != "0" ]]; then
    print_error "❌ (4) payment-service 컨테이너가 여전히 ${RUNNING_AFTER_STOP}개 실행 중"
    exit 3
fi

print_info "✅ (4) payment-service 전체 정지 확인 — 실행 중 컨테이너 0"
echo ""

# ---------------------------------------------------------------------------
# (5) (2)와 (3) 을 한 번 더 즉시 재확인한 직후에만 비우고 재시드
# ---------------------------------------------------------------------------
# 여기서는 안정 확인(연속 N 회)을 다시 요구하지 않는다 — (2)/(3) 이 이미 안정을 확인했고
# payment-service 가 (4) 로 멈춰 새 결제·새 재고 확정 메시지가 나갈 출처가 없다. 재확인은
# 단발 조회로 좁혀 확인과 비우기 사이의 창을 최소로 유지한다. 격리는 payment-service 가
# 멈춘 뒤라 관리자 종결 자동 시도를 다시 하지 않는다(호출할 살아있는 인스턴스가 없다) —
# 여기서 격리가 남아 있다면 (2) 이후 새로 발생한 것으로 원인 불문 실패 처리한다.

print_section "▶ (5) (2)/(3) 즉시 재확인 — 통과 직후에만 비운다"

RECHECK_UNSETTLED=$(count_unsettled)
RECHECK_QUARANTINED=$(count_quarantined)
RECHECK_NOISE=$(count_noise)
RECHECK_LAG=$(kafka_lag_sum)

RECHECK_UNSETTLED="${RECHECK_UNSETTLED:-0}"
RECHECK_QUARANTINED="${RECHECK_QUARANTINED:-0}"
RECHECK_NOISE="${RECHECK_NOISE:-0}"

if [[ "${RECHECK_LAG}" == "ERROR" ]]; then
    print_error "❌ (5) 재확인 실패 — 소비 적체 조회 불가. payment-service 는 이미 정지된 상태다"
    exit 4
fi

if [[ "${RECHECK_UNSETTLED}" -ne 0 || "${RECHECK_QUARANTINED}" -ne 0 || "${RECHECK_NOISE}" -ne 0 || "${RECHECK_LAG}" -ne 0 ]]; then
    print_error "❌ (5) 재확인 실패 — 미종결=${RECHECK_UNSETTLED} 격리=${RECHECK_QUARANTINED} 미회수 선차감 기록=${RECHECK_NOISE} 소비 적체=${RECHECK_LAG}"
    print_error "   payment-service 는 이미 정지된 상태다 — 캐시를 비우지 않는다. 원인을 확인한 뒤 재실행하세요"
    exit 4
fi

print_info "✅ (5) 재확인 통과 — 미종결=0 격리=0 미회수 선차감 기록=0 소비 적체=0"

print_section "  캐시 비우기 + 상품별 상수 재시드 위임 → scripts/bench-seed-stock.sh"
if ! bash "${ROOT_DIR}/scripts/bench-seed-stock.sh"; then
    print_error "❌ (5) 재시드 실패 — 캐시와 원본이 어긋난 상태일 수 있다. 즉시 확인 필요"
    exit 4
fi

echo ""
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_info "✅ bench-cycle-reset 완료 — 다섯 단계 전부 통과, 캐시 비우기 + 재시드 완료"
print_section "  payment-service 는 (4) 에서 정지된 채로 남는다 — 다음 사이클의 스택 기동 단계가 재기동한다"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
