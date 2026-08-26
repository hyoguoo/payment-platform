#!/usr/bin/env bash
# verify-settlement.sh — settle 대기 후 DB 결제 종결 분포 ↔ k6 카운트 교차 검증
#                        + payment_history e2e 처리 시각 + 상품별 재고 정합 교차검증
#                        + 종결 결제 ↔ 선차감 기록 건별 대조
#
# 용도:
#   run-benchmark.sh 완료 후 실행. reconciler settle 대기(최악 scan 1주기 상한)를
#   마친 뒤 payment_event status별 DB 카운트와 k6 결과 JSON 카운트를 교차한다.
#   settle 종결 후 시드된 상품 전부에 대해 redis-stock 잔여재고 vs product RDB
#   차감 합 교차검증을 수행하고, 종결된 결제마다 선차감 기록 상태가 결제 상태와
#   부합하는지 건별로 대조한다.
#
#   교차식:
#     [1] k6(DONE + FAILED + timeout) == DB(DONE + FAILED + QUARANTINED + 미종결)
#     [2] k6(DONE)                    == DB(DONE)
#     [3] 재고 정합(상품별): 미종결=0 AND QUARANTINED=0 AND 미회수 선차감 기록=0
#         AND 소비 적체 게이트 통과 선결 후, 상품마다 redis 잔여 == RDB 잔여
#     [4] 건별 대조: DONE 결제의 선차감 기록은 전부 COMMITTED, FAILED 결제의
#         선차감 기록은 전부 REVERTED — 어긋나면 총건수가 맞아도 개별 유실/오류를 잡는다
#
#   불일치 해석:
#     - e2e_timeout 중 settle 후 DONE → 지연 종결(k6 타임아웃 내 미도달했으나 후속 settle)
#     - settle 후 미종결(READY/IN_PROGRESS/RETRYING) → 아직 종결이 덜 끝난 상태.
#       대기하면 풀릴 수 있어 판단 보류로 다룬다
#     - 미회수 선차감 기록(stock_hold_record.status=NOISE) → 주기 회수(StockHoldRecoveryWorker)로
#       풀릴 수 있어 판단 보류로 다룬다
#     - 재고 확정 메시지 소비 적체(consumer group) → 재고 확정 발행이 트랜잭션으로 묶여 있어
#       커밋 표시가 파티션마다 오프셋을 하나씩 차지하는데 컨슈머는 그것을 레코드로 처리하지
#       않는다. 그래서 적체 0 은 실측에서 도달 불가로 드러났고, 게이트를 "파티션 수 이하 +
#       재확인에서 더 줄지 않음"으로 바꿨다. 파티션 수를 넘거나 아직 줄고 있는 중이면 진짜
#       소비가 도는 중이라 판단 보류로 다룬다 — 남은 채로 재면 실제로는 정상인데 재고
#       정합이 불일치로 찍힌다. 이 게이트는 소비가 끝났다는 정황일 뿐, 정합 판정의 실제
#       권한자는 아래 [3] 상품별 캐시-원본 대조다 — 소비가 안 끝났으면 원본이 아직 안
#       깎여 그 대조에서 불일치로 잡힌다. 단, 그룹에 배정된 살아있는 컨슈머가 하나도 없는
#       채로 적체가 남아 있으면(product-service 다운) 대기해도 절대 안 풀리므로 판단 보류로
#       묶지 않고 접속·전제 실패(exit 1)로 즉시 실패시킨다
#     - QUARANTINED > 0 → 격리는 사람 판단(관리자 종결)이 있어야 풀린다. 대기로 안 풀리므로
#       판단 보류가 아니라 불일치로 낸다
#     - 교차식 [2](k6 관측 DONE == DB DONE)가 어긋나도, 그 차이가 k6 포기 건수(e2e_timeout)
#       이내이고 교차식 [1]과 [3]/[4]가 전부 통과하면 불일치로 내지 않는다. 포화 구간에서는
#       k6 가 폴링을 포기(POLL_TIMEOUT_MS)한 뒤에도 DB 에서는 뒤늦게 DONE 으로 종결되는 건이
#       늘 생긴다 — 관측이 잘린 것이지 유실이 아니다. 유실 여부는 총건수 교차식([1])과
#       상품별·건별 대조([3][4])가 맡는다. 차이가 포기 건수보다 크면(k6 가 관측했는데 DB 에
#       없거나, 포기 건수를 넘어서는 차이) 그건 설명되지 않는 차이이므로 그대로 불일치다
#
# settle 대기 계산 (SETTLE_WAIT_SECONDS 미지정 시 자동 산출):
#   RECONCILER_TIMEOUT + ceil(RECONCILER_SCAN_MS / 1000) + 여유(12s)
#   예) RECONCILER_TIMEOUT=30, RECONCILER_SCAN_MS=15000 → 30 + 15 + 12 = 57s
#   예) RECONCILER_TIMEOUT=600, RECONCILER_SCAN_MS=15000 → 600 + 15 + 12 = 627s
#
# 재고 정합식 상세 (상품별로 동일하게 적용):
#   - redis-stock 잔여 = 초기시드 − DECR 누계(확인요청) + INCR 누계(FAILED·pg보상)
#     → 종결 완료(미종결=0) AND QUARANTINED=0 AND 미회수 선차감 기록=0 AND 소비 적체=0
#       상태에서만 RDB 잔여와 등식 성립
#   - 클러스터 구성(redis-stock-cluster)에서는 키가 상품 해시태그로 슬롯에 흩어져
#     조회가 다른 마스터로 리다이렉트될 수 있다 — redis-cli를 클러스터 모드(-c)로 불러
#     MOVED 리다이렉트를 따라간다(bench-seed-stock.sh와 동일한 방식)
#
# 사용법:
#   bash scripts/k6/verify-settlement.sh
#   CASE_NAME=async-low bash scripts/k6/verify-settlement.sh
#   CASE_NAME=async-low SETTLE_WAIT_SECONDS=0 bash scripts/k6/verify-settlement.sh  # 대기 스킵
#
# 환경 변수:
#   CASE_NAME               — 검증 대상 케이스명 (기본: async-low)
#   RESULTS_DIR             — results JSON 위치 (기본: {ROOT_DIR}/results)
#   SETTLE_WAIT_SECONDS     — settle 대기 시간(초). 미지정 시 RECONCILER_TIMEOUT 기반 자동 산출.
#   RECONCILER_TIMEOUT      — reconciler IN_PROGRESS 회수 기준(초, 기본: 30) — 자동 산출 입력값
#   RECONCILER_SCAN_MS      — reconciler 스캔 주기(ms, 기본: 15000) — 자동 산출 입력값
#   MYSQL_PAYMENT_CONTAINER — payment DB 컨테이너명 (기본: payment-mysql-payment)
#   MYSQL_PAYMENT_DB        — payment DB명 (기본: payment-platform)
#   MYSQL_PAYMENT_USER      — MySQL 사용자 (기본: root)
#   MYSQL_PAYMENT_PASSWORD  — MySQL 패스워드 (기본: payment123)
#   MYSQL_PRODUCT_CONTAINER — product DB 컨테이너명 (기본: payment-mysql-product)
#   MYSQL_PRODUCT_DB        — product DB명 (기본: product)
#   MYSQL_PRODUCT_USER      — MySQL 사용자 (기본: root)
#   MYSQL_PRODUCT_PASSWORD  — MySQL 패스워드 (기본: payment123)
#   REDIS_STOCK_CONTAINER   — redis-stock(-cluster) 컨테이너명 (기본: payment-redis-stock).
#                             클러스터 구성이면 마스터 노드 하나만 지정해도 -c가 리다이렉트를 따라간다
#   KAFKA_CONTAINER         — kafka 컨테이너명 (기본: payment-kafka)
#   STOCK_COMMIT_GROUP      — 재고 확정 소비자 그룹 (기본: product-service-stock-commit)
#   STOCK_COMMIT_LAG_RECHECK_INTERVAL_SECONDS — 소비 적체 게이트 재확인 간격 초(기본: 3) —
#                             파티션 수 이하라도 직전 읽음보다 줄었으면 아직 소비 중으로 본다
#   PRODUCT_COUNT           — 재고 정합 대조 대상 상품 종류 수 (기본: 100 — bench-seed-stock.sh와 동일)
#   PRODUCT_ID_BASE         — 대조 대상 상품 id 시작값 (기본: 1000 — bench-seed-stock.sh와 동일)
#
# 선행 조건:
#   - run-benchmark.sh 완료 (results/<CASE_NAME>.json 존재)
#   - benchmark compose 스택 기동 중
#   - jq 설치 (JSON 파싱)
#
# 결과 파일:
#   results/<CASE_NAME>-verdict.json — 이 스크립트의 최종 판정(verdict/exit_code/reason +
#   세부 카운트)을 기계가 읽을 수 있게 남긴다. 부하 도구가 쓰는 results/<CASE_NAME>.json은
#   건드리지 않는다 — 사후에 필드를 끼워 넣으면 그 파일을 읽는 다른 도구와 스키마가 어긋난다.
#
# 종료 코드:
#   0 — 통과(PASS) — 상품 전부 재고 정합 + 건별 대조 일치 + 격리·미종결·미회수 선차감 기록·
#       소비 적체 전부 0
#   1 — 접속·전제 실패 — jq 미설치, Docker 미기동, k6 결과 파일 없음, DB/Redis/Kafka 접속 실패
#   2 — 판단 보류(INCONCLUSIVE) — 미종결/미회수 선차감 기록/소비 적체 중 하나라도 남아 아직
#       판정할 수 없다. 대기 후 재실행하면 풀릴 수 있다
#   3 — 불일치(MISMATCH) — 격리 결제 잔류(대기로 풀리지 않음), 또는 교차식/상품별 재고 정합/
#       건별 대조 중 하나라도 어긋남

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# shellcheck source=../common.sh
source "${ROOT_DIR}/scripts/common.sh"

# ---------------------------------------------------------------------------
# 환경 변수 기본값
# ---------------------------------------------------------------------------

CASE_NAME="${CASE_NAME:-async-low}"
RESULTS_DIR="${RESULTS_DIR:-${ROOT_DIR}/results}"
RECONCILER_TIMEOUT="${RECONCILER_TIMEOUT:-30}"
RECONCILER_SCAN_MS="${RECONCILER_SCAN_MS:-15000}"

# SETTLE_WAIT_SECONDS: 명시 지정 시 그 값 우선. 미지정 시 자동 산출.
#   자동 산출식: RECONCILER_TIMEOUT + ceil(RECONCILER_SCAN_MS / 1000) + 12(여유)
#   12초 = OutboxWorker(2s) + pg 왕복(5s) + 콘솔 출력 마진(5s)
if [[ -z "${SETTLE_WAIT_SECONDS:-}" ]]; then
    RECONCILER_SCAN_SEC=$(( (RECONCILER_SCAN_MS + 999) / 1000 ))
    SETTLE_WAIT_SECONDS=$(( RECONCILER_TIMEOUT + RECONCILER_SCAN_SEC + 12 ))
    SETTLE_WAIT_AUTO=true
else
    SETTLE_WAIT_AUTO=false
fi

MYSQL_PAYMENT_CONTAINER="${MYSQL_PAYMENT_CONTAINER:-payment-mysql-payment}"
MYSQL_CONTAINER="${MYSQL_PAYMENT_CONTAINER}"
MYSQL_DB="${MYSQL_PAYMENT_DB:-payment-platform}"
MYSQL_USER="${MYSQL_PAYMENT_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PAYMENT_PASSWORD:-payment123}"

MYSQL_PRODUCT_CONTAINER="${MYSQL_PRODUCT_CONTAINER:-payment-mysql-product}"
MYSQL_PRODUCT_DB="${MYSQL_PRODUCT_DB:-product}"
MYSQL_PRODUCT_USER="${MYSQL_PRODUCT_USER:-root}"
MYSQL_PRODUCT_PASSWORD="${MYSQL_PRODUCT_PASSWORD:-payment123}"

REDIS_STOCK_CONTAINER="${REDIS_STOCK_CONTAINER:-payment-redis-stock}"

KAFKA_CONTAINER="${KAFKA_CONTAINER:-payment-kafka}"
STOCK_COMMIT_GROUP="${STOCK_COMMIT_GROUP:-product-service-stock-commit}"

PRODUCT_COUNT="${PRODUCT_COUNT:-100}"
PRODUCT_ID_BASE="${PRODUCT_ID_BASE:-1000}"
PRODUCT_LAST_ID=$((PRODUCT_ID_BASE + PRODUCT_COUNT - 1))

RESULT_JSON="${RESULTS_DIR}/${CASE_NAME}.json"
VERDICT_JSON="${RESULTS_DIR}/${CASE_NAME}-verdict.json"

mysql_payment_query() {
    docker exec -i "${MYSQL_CONTAINER}" mysql \
        -u "${MYSQL_USER}" -p"${MYSQL_PASSWORD}" \
        -D "${MYSQL_DB}" -N -B -e "$1" 2>/dev/null
}

# ---------------------------------------------------------------------------
# 배너
# ---------------------------------------------------------------------------

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ verify-settlement.sh — DB 종결 분포 ↔ k6 교차 검증"
print_section "  CASE_NAME=${CASE_NAME}"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# ---------------------------------------------------------------------------
# 선행 조건 확인
# ---------------------------------------------------------------------------

# 1. jq 설치 확인
if ! command -v jq >/dev/null 2>&1; then
    print_error "❌ jq 가 설치되어 있지 않습니다."
    echo ""
    echo "  설치 방법:"
    echo "    macOS:  brew install jq"
    echo "    Linux:  apt-get install jq  /  yum install jq"
    echo ""
    exit 1
fi
print_info "✅ jq 설치 확인: $(jq --version)"

# 2. Docker 데몬 확인
check_docker

# 3. k6 결과 JSON 확인
if [[ ! -f "${RESULT_JSON}" ]]; then
    print_error "❌ k6 결과 파일 없음: ${RESULT_JSON}"
    echo ""
    echo "  run-benchmark.sh 를 먼저 실행하거나 CASE_NAME 을 올바르게 설정하세요."
    echo "    bash scripts/k6/run-benchmark.sh"
    echo "    CASE_NAME=async-high bash scripts/k6/verify-settlement.sh"
    echo ""
    exit 1
fi
print_info "✅ k6 결과 파일 확인: ${RESULT_JSON}"

echo ""

# ---------------------------------------------------------------------------
# settle 대기
# ---------------------------------------------------------------------------

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ settle 대기 (${SETTLE_WAIT_SECONDS}초)"
if [[ "${SETTLE_WAIT_AUTO}" == "true" ]]; then
    RECONCILER_SCAN_SEC_DISPLAY=$(( (RECONCILER_SCAN_MS + 999) / 1000 ))
    print_section "  [자동 산출] RECONCILER_TIMEOUT=${RECONCILER_TIMEOUT}s"
    print_section "              + ceil(RECONCILER_SCAN_MS/1000)=${RECONCILER_SCAN_SEC_DISPLAY}s"
    print_section "              + 여유 12s = ${SETTLE_WAIT_SECONDS}s"
else
    print_section "  [명시 지정] SETTLE_WAIT_SECONDS=${SETTLE_WAIT_SECONDS}s"
    print_section "  참고: RECONCILER_TIMEOUT=${RECONCILER_TIMEOUT}s / RECONCILER_SCAN_MS=${RECONCILER_SCAN_MS}ms"
fi
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [[ "${SETTLE_WAIT_SECONDS}" -gt 0 ]]; then
    print_warning "  ${SETTLE_WAIT_SECONDS}초 대기 중 (SETTLE_WAIT_SECONDS=0 으로 스킵 가능)..."
    echo -n "  "
    remaining="${SETTLE_WAIT_SECONDS}"
    while [[ "${remaining}" -gt 0 ]]; do
        echo -n "${remaining}s "
        sleep 5 2>/dev/null || sleep 1
        if [[ "${remaining}" -ge 5 ]]; then
            remaining=$(( remaining - 5 ))
        else
            remaining=0
        fi
    done
    echo ""
    print_info "  ✅ settle 대기 완료"
else
    print_warning "  ⚠️  SETTLE_WAIT_SECONDS=0 — 대기 스킵 (즉시 스냅샷)"
fi

echo ""

# ---------------------------------------------------------------------------
# k6 결과 JSON 파싱
# ---------------------------------------------------------------------------

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ k6 카운트 추출"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# handleSummary 가 출력한 metrics 구조:
#   metrics.confirm_requests_count  — confirm 요청 총 건수
#   metrics.payment_failed_count    — FAILED 종결 건수
#   metrics.e2e_timeout_count       — 폴링 타임아웃(POLL_TIMEOUT_MS 초과) 건수
#   k6 DONE = confirm_requests - payment_failed - e2e_timeout
#   (checkout 실패/중복/amount 오류로 confirm 미진입한 iteration 은 confirm_requests 에 미포함)

K6_CONFIRM=$(jq '.metrics.confirm_requests_count // 0' "${RESULT_JSON}")
K6_FAILED=$(jq '.metrics.payment_failed_count // 0' "${RESULT_JSON}")
K6_TIMEOUT=$(jq '.metrics.e2e_timeout_count // 0' "${RESULT_JSON}")
K6_DONE=$(( K6_CONFIRM - K6_FAILED - K6_TIMEOUT ))
K6_TOTAL=$(( K6_DONE + K6_FAILED + K6_TIMEOUT ))

echo "  k6 confirm 총 요청수:  ${K6_CONFIRM}"
echo "  k6 DONE:               ${K6_DONE}  (confirm - FAILED - timeout)"
echo "  k6 FAILED:             ${K6_FAILED}"
echo "  k6 e2e_timeout:        ${K6_TIMEOUT}"
echo "  ─────────────────────────────────────"
echo "  k6 총합(DONE+FAILED+timeout): ${K6_TOTAL}"

echo ""

# ---------------------------------------------------------------------------
# payment DB 집계
# ---------------------------------------------------------------------------

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ payment DB 집계 (payment_event.status)"
print_section "  컨테이너: ${MYSQL_CONTAINER} / DB: ${MYSQL_DB}"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# payment_event status 별 카운트 조회
DB_RAW=$(mysql_payment_query \
    "SELECT status, COUNT(*) FROM payment_event GROUP BY status ORDER BY status;") || {
    print_error "❌ DB 접속 실패 — 컨테이너(${MYSQL_CONTAINER}) 또는 인증 확인"
    echo ""
    echo "  컨테이너 상태: $(docker inspect -f '{{.State.Status}}' "${MYSQL_CONTAINER}" 2>/dev/null || echo 'not found')"
    exit 1
}

# 상태별 카운트 파싱
extract_count() {
    local status_name="${1}"
    echo "${DB_RAW}" | awk -v s="${status_name}" '$1 == s { print $2 }' | head -1
}

DB_DONE_RAW=$(extract_count "DONE")
DB_FAILED_RAW=$(extract_count "FAILED")
DB_QUARANTINED_RAW=$(extract_count "QUARANTINED")
DB_READY_RAW=$(extract_count "READY")
DB_IN_PROGRESS_RAW=$(extract_count "IN_PROGRESS")
DB_RETRYING_RAW=$(extract_count "RETRYING")
DB_CANCELED_RAW=$(extract_count "CANCELED")
DB_PARTIAL_CANCELED_RAW=$(extract_count "PARTIAL_CANCELED")
DB_EXPIRED_RAW=$(extract_count "EXPIRED")

# 빈 값은 0으로 대체
DB_DONE="${DB_DONE_RAW:-0}"
DB_FAILED="${DB_FAILED_RAW:-0}"
DB_QUARANTINED="${DB_QUARANTINED_RAW:-0}"
DB_READY="${DB_READY_RAW:-0}"
DB_IN_PROGRESS="${DB_IN_PROGRESS_RAW:-0}"
DB_RETRYING="${DB_RETRYING_RAW:-0}"
DB_CANCELED="${DB_CANCELED_RAW:-0}"
DB_PARTIAL_CANCELED="${DB_PARTIAL_CANCELED_RAW:-0}"
DB_EXPIRED="${DB_EXPIRED_RAW:-0}"

# 미종결(READY/IN_PROGRESS/RETRYING) — settle 후에도 남아있으면 대기하면 풀릴 수 있는 판단 보류
DB_UNSETTLED=$(( DB_READY + DB_IN_PROGRESS + DB_RETRYING ))

# k6 교차 대상 총합: 부하 측정으로 생성된 DONE + FAILED + QUARANTINED + 미종결
# (CANCELED/PARTIAL_CANCELED/EXPIRED 는 결제 플로우 외 경로 — 부하 측정 대상에서 분리)
DB_TOTAL=$(( DB_DONE + DB_FAILED + DB_QUARANTINED + DB_UNSETTLED ))

echo ""
echo "  ┌─────────────────────────────────────────────────┐"
echo "  │  payment_event status 분포 (전체 테이블)        │"
echo "  ├──────────────────────────┬──────────────────────┤"
echo "  │  상태                    │  카운트              │"
echo "  ├──────────────────────────┼──────────────────────┤"
printf "  │  %-24s│  %-20s│\n" "DONE (종결)"           "${DB_DONE}"
printf "  │  %-24s│  %-20s│\n" "FAILED (종결)"         "${DB_FAILED}"
printf "  │  %-24s│  %-20s│\n" "QUARANTINED (비종결)"  "${DB_QUARANTINED}"
printf "  │  %-24s│  %-20s│\n" "READY (미종결)"        "${DB_READY}"
printf "  │  %-24s│  %-20s│\n" "IN_PROGRESS (미종결)"  "${DB_IN_PROGRESS}"
printf "  │  %-24s│  %-20s│\n" "RETRYING (미종결)"     "${DB_RETRYING}"
printf "  │  %-24s│  %-20s│\n" "CANCELED"              "${DB_CANCELED}"
printf "  │  %-24s│  %-20s│\n" "PARTIAL_CANCELED"      "${DB_PARTIAL_CANCELED}"
printf "  │  %-24s│  %-20s│\n" "EXPIRED"               "${DB_EXPIRED}"
echo "  ├──────────────────────────┼──────────────────────┤"
printf "  │  %-24s│  %-20s│\n" "교차 대상 합계"        "${DB_TOTAL}"
echo "  │  (DONE+FAILED+Q+미종결)  │                      │"
echo "  └──────────────────────────┴──────────────────────┘"

echo ""

# ---------------------------------------------------------------------------
# 미회수 선차감 기록(stock_hold_record.status=NOISE) + 재고 확정 소비 적체
# ---------------------------------------------------------------------------
# 둘 다 대기하면 풀릴 수 있는 판단 보류 재료다 — 미회수 기록은 주기 회수
# (StockHoldRecoveryWorker), 소비 적체는 컨슈머가 밀린 메시지를 따라잡으면 풀린다.
# 남은 채로 재고 정합을 재면 실제로는 정상인데 불일치로 찍힌다. 소비 적체 게이트는
# "파티션 수 이하 + 더 줄지 않음"이다 — 재고 확정 발행이 트랜잭션으로 묶여 있어 커밋
# 표시가 파티션마다 오프셋을 하나씩 차지하는데 컨슈머는 그것을 레코드로 처리하지 않으므로
# 적체 0 은 도달 불가하다. 이 게이트는 정황 증거일 뿐 정합 판정의 실제 권한자는 아래
# [3] 상품별 캐시-원본 대조다 — 소비가 안 끝났으면 원본이 아직 안 깎여 그 대조가 잡아낸다.
# ---------------------------------------------------------------------------

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ 미회수 선차감 기록 + 재고 확정 소비 적체"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

DB_NOISE_RAW=$(mysql_payment_query \
    "SELECT COUNT(*) FROM stock_hold_record WHERE status = 'NOISE';") || {
    print_error "❌ DB 접속 실패 — stock_hold_record 조회 실패"
    exit 1
}
DB_NOISE="${DB_NOISE_RAW:-0}"

# 소비 적체 합계를 구하되, 그룹 행이 아예 없을 때(그룹 조회 실패·존재하지 않는 그룹)와
# 배정된 살아있는 컨슈머가 없을 때(product-service 다운)를 구분해 알린다.
# --describe 출력 컬럼: GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID
# CONSUMER-ID 가 "-"면 그 파티션에 배정된 살아있는 컨슈머가 없다는 뜻이다(오프셋 자체는
# 여전히 조회된다) — 이 상태에서 적체가 남아 있으면 소비자가 돌아오기 전까지 절대 안 줄어든다.
#
# 반환값(공백 구분 두 값 "합계 파티션수"): 정상 — "<합계> <파티션수>" / "ERROR 0" — 그룹
# 조회 실패 또는 그룹 행 없음(과거 버전은 이 경우도 seen=0 → 0 을 출력해 판정 게이트를
# 조용히 통과시켰다) / "NO_CONSUMER <파티션수>" — 적체가 남아 있는데 배정된 컨슈머가
# 하나도 없음(대기해도 안 풀린다)
kafka_stock_commit_lag() {
    local out
    out=$(docker exec "${KAFKA_CONTAINER}" kafka-consumer-groups \
        --bootstrap-server localhost:9092 --describe --group "${STOCK_COMMIT_GROUP}" 2>/dev/null)
    if [[ -z "${out}" ]]; then
        echo "ERROR 0"
        return
    fi
    echo "${out}" | awk '
        $1 == "GROUP" { next }
        NF >= 7 && $6 ~ /^[0-9]+$/ {
            sum += $6
            partitions++
            if ($7 != "-") { live = 1 }
        }
        END {
            if (!partitions) { print "ERROR 0"; exit }
            if (sum > 0 && !live) { print "NO_CONSUMER", partitions; exit }
            print sum, partitions
        }
    '
}

STOCK_COMMIT_LAG_RECHECK_INTERVAL_SECONDS="${STOCK_COMMIT_LAG_RECHECK_INTERVAL_SECONDS:-3}"

read -r STOCK_COMMIT_LAG STOCK_COMMIT_PARTITIONS <<< "$(kafka_stock_commit_lag)"
if [[ "${STOCK_COMMIT_LAG}" == "ERROR" ]]; then
    print_error "❌ 소비 적체 조회 실패 — 컨테이너(${KAFKA_CONTAINER}) 또는 그룹(${STOCK_COMMIT_GROUP}) 확인 필요"
    exit 1
fi
if [[ "${STOCK_COMMIT_LAG}" == "NO_CONSUMER" ]]; then
    print_error "❌ 소비 적체 있음 + 그룹(${STOCK_COMMIT_GROUP})에 배정된 살아있는 컨슈머가 없다"
    print_error "   product-service 가 내려가 있으면 대기해도 절대 줄지 않는다 — 판단 보류가 아니라 즉시 실패로 다룬다"
    exit 1
fi

# 게이트: 파티션 수 이하 + 더 줄지 않음. 단발 스크립트라 폴링 루프 대신 짧은 간격을 두고
# 한 번 더 읽어 추세를 본다 — 두 번째 읽음이 첫 번째보다 줄었다면 아직 진짜 소비가 도는
# 중이라는 뜻이라(파티션 수 이하라도) 통과로 보지 않고 STOCK_COMMIT_LAG_PENDING 을 세운다.
STOCK_COMMIT_LAG_PENDING=false
if [[ "${STOCK_COMMIT_LAG}" -gt 0 ]]; then
    sleep "${STOCK_COMMIT_LAG_RECHECK_INTERVAL_SECONDS}"
    read -r STOCK_COMMIT_LAG_RECHECK STOCK_COMMIT_PARTITIONS_RECHECK <<< "$(kafka_stock_commit_lag)"
    if [[ "${STOCK_COMMIT_LAG_RECHECK}" == "ERROR" ]]; then
        print_error "❌ 소비 적체 재확인 실패 — 컨테이너(${KAFKA_CONTAINER}) 또는 그룹(${STOCK_COMMIT_GROUP}) 확인 필요"
        exit 1
    fi
    if [[ "${STOCK_COMMIT_LAG_RECHECK}" == "NO_CONSUMER" ]]; then
        print_error "❌ 소비 적체 재확인 중 컨슈머 소실 — 그룹(${STOCK_COMMIT_GROUP})에 배정된 살아있는 컨슈머가 없다"
        exit 1
    fi
    if [[ "${STOCK_COMMIT_LAG_RECHECK}" -lt "${STOCK_COMMIT_LAG}" ]]; then
        print_warning "  ⚠️  소비 적체가 ${STOCK_COMMIT_LAG} → ${STOCK_COMMIT_LAG_RECHECK} 로 줄어드는 중 — 진짜 소비 중이라 판단 보류로 다룬다"
        STOCK_COMMIT_LAG_PENDING=true
    fi
    STOCK_COMMIT_LAG="${STOCK_COMMIT_LAG_RECHECK}"
    STOCK_COMMIT_PARTITIONS="${STOCK_COMMIT_PARTITIONS_RECHECK}"
fi
if [[ "${STOCK_COMMIT_LAG}" -gt "${STOCK_COMMIT_PARTITIONS}" ]]; then
    STOCK_COMMIT_LAG_PENDING=true
fi

echo ""
echo "  미회수 선차감 기록(NOISE):     ${DB_NOISE}"
echo "  재고 확정 소비 적체(${STOCK_COMMIT_GROUP}): ${STOCK_COMMIT_LAG} (파티션수=${STOCK_COMMIT_PARTITIONS}, 게이트 대기=${STOCK_COMMIT_LAG_PENDING})"
echo ""

# ---------------------------------------------------------------------------
# payment_history 기반 e2e 처리 시각 산출
# ---------------------------------------------------------------------------
# payment_event.last_status_changed_at 는 last-write 단조성 함정(마지막 상태 전이만
# 기록, DONE 이후 갱신 가능)이 있어 측정 오류 유발.
# payment_history(append-only) 에서 current_status='DONE' 최초 전이 시각을 집계한다:
#   MIN(change_status_at) WHERE current_status='DONE' — per order_id 기준
# e2e_p50/p95 는 MySQL 내장 집계로 도출한다(JVM 없이 DB 단에서 계산).
# ---------------------------------------------------------------------------

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ payment_history 기반 e2e 처리 시각"
print_section "  (MIN(change_status_at) WHERE current_status='DONE' per order_id)"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# payment_history 에서 각 order_id 의 최초 DONE 전이 시각을 구하고,
# 가장 오래된 DONE(측정 시작 기준점)과 가장 최신 DONE(측정 종료 기준점)을 추출한다.
# e2e_p50 / e2e_p95 는 결제 시작~DONE 구간을 order_id 단위 MIN(change_status_at) 기준으로 산출한다.
# 단, k6 confirm 시각(UTC ms)은 k6 JSON에 없으므로 여기서는 처리 분포(DONE 도달 시각 분포)를 리포트한다.
HISTORY_RAW=$(mysql_payment_query \
    "SELECT
         COUNT(*)                                          AS done_count,
         MIN(first_done_at)                               AS earliest_done,
         MAX(first_done_at)                               AS latest_done,
         TIMESTAMPDIFF(SECOND, MIN(first_done_at), MAX(first_done_at)) AS span_sec
     FROM (
         SELECT order_id, MIN(change_status_at) AS first_done_at
         FROM payment_history
         WHERE current_status = 'DONE'
         GROUP BY order_id
     ) sub;") || {
    print_warning "  ⚠️  payment_history 조회 실패 — DB 접속 또는 테이블 없음 (스킵)"
    HISTORY_RAW=""
}

if [[ -n "${HISTORY_RAW}" ]]; then
    HIST_DONE_COUNT=$(echo "${HISTORY_RAW}" | awk 'NR==1{print $1}')
    HIST_EARLIEST=$(echo "${HISTORY_RAW}"   | awk 'NR==1{print $2, $3}')
    HIST_LATEST=$(echo "${HISTORY_RAW}"     | awk 'NR==1{print $4, $5}')
    HIST_SPAN=$(echo "${HISTORY_RAW}"       | awk 'NR==1{print $6}')

    echo ""
    echo "  DONE 전이 기록 건수:  ${HIST_DONE_COUNT:-0}"
    echo "  최초 DONE 시각:       ${HIST_EARLIEST:-N/A}"
    echo "  최종 DONE 시각:       ${HIST_LATEST:-N/A}"
    echo "  전체 처리 span:       ${HIST_SPAN:-N/A}s (최초→최종 DONE 간격)"
    echo ""
    echo "  ※ k6 confirm 시각과의 정밀 e2e 분포는 k6 JSON 라인 로그 join 필요"
    echo "    (payment_history 는 처리 완료 분포 SSOT — 폴링 체감과 독립 계측)"
else
    echo "  (payment_history 조회 스킵)"
fi

echo ""

# ---------------------------------------------------------------------------
# redis-stock 헬스 확인 (QUARANTINED 트리아지용)
# ---------------------------------------------------------------------------

REDIS_STOCK_HEALTH=$(docker inspect -f '{{.State.Health.Status}}' "${REDIS_STOCK_CONTAINER}" 2>/dev/null || echo "unknown")

# ---------------------------------------------------------------------------
# 교차식 검증
# ---------------------------------------------------------------------------

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ 교차식 검증"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

echo ""

# [1] k6 총합 == DB 총합
print_section "  [1] k6(DONE+FAILED+timeout) == DB(DONE+FAILED+QUARANTINED+미종결)"
echo "      k6 총합: ${K6_TOTAL}  /  DB 총합: ${DB_TOTAL}"

CROSS_1_OK=false
if [[ "${K6_TOTAL}" -eq "${DB_TOTAL}" ]]; then
    CROSS_1_OK=true
    print_info "      ✅ 일치 — 총 건수 정합"
else
    DIFF_1=$(( K6_TOTAL - DB_TOTAL ))
    print_warning "      ⚠️  불일치 (k6 - DB = ${DIFF_1})"
fi

echo ""

# [2] k6 DONE == DB DONE — 어긋나도 그 차이가 k6 포기 건수(K6_TIMEOUT) 이내면 관측이
# 잘린 것으로 보고 설명된 차이로 다룬다(유실 여부는 [1]/[3]/[4]가 맡는다). 자세한 근거는
# 파일 상단 "불일치 해석" 참고
print_section "  [2] k6(DONE) == DB(DONE)"
echo "      k6 DONE: ${K6_DONE}  /  DB DONE: ${DB_DONE}  /  k6 포기(timeout): ${K6_TIMEOUT}"

CROSS_2_OK=false
CROSS_2_EXPLAINED=false
DIFF_2=$(( DB_DONE - K6_DONE ))
if [[ "${K6_DONE}" -eq "${DB_DONE}" ]]; then
    CROSS_2_OK=true
    print_info "      ✅ 일치 — DONE 정합"
elif [[ "${DIFF_2}" -ge 0 ]] && [[ "${DIFF_2}" -le "${K6_TIMEOUT}" ]]; then
    CROSS_2_EXPLAINED=true
    print_warning "      ℹ️  차이 있으나 관측 포기 건수로 설명됨 (DB - k6 = ${DIFF_2} <= timeout ${K6_TIMEOUT})"
else
    print_warning "      ⚠️  불일치 — 포기 건수로 설명 안 됨 (DB - k6 = ${DIFF_2}, timeout=${K6_TIMEOUT})"
fi

echo ""

# ---------------------------------------------------------------------------
# QUARANTINED 트리아지 (baseline failRate=0 에서 발생 불가)
# ---------------------------------------------------------------------------

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ QUARANTINED 트리아지"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

echo ""
if [[ "${DB_QUARANTINED}" -gt 0 ]]; then
    print_error "  ❌ QUARANTINED = ${DB_QUARANTINED} — 격리는 관리자 종결이 있어야 풀린다. 대기로 안 풀리므로 불일치로 낸다"
    echo ""
    echo "  트리아지 절차:"
    echo "    1. redis-stock 헬스 확인"
    echo "       컨테이너: ${REDIS_STOCK_CONTAINER} / 현재 상태: ${REDIS_STOCK_HEALTH}"
    echo "       명령: docker exec ${REDIS_STOCK_CONTAINER} redis-cli ping"
    echo ""
    echo "    2. redis-stock 이 비정상이면 QUARANTINED 는 재고 차감 실패로 인한"
    echo "       CACHE_DOWN 경로 진입 가능성이 높다."
    echo "       → bench-seed-stock.sh 를 재실행하고 측정을 반복하세요."
    echo ""
    echo "    3. redis-stock 정상이면 reconciler 회수 기준(RECONCILER_TIMEOUT)"
    echo "       이 너무 짧거나 pg-service 응답 지연이 극단적으로 길었던 경우."
    echo "       → RECONCILER_TIMEOUT 값 확인: ${RECONCILER_TIMEOUT}s"
else
    print_info "  ✅ QUARANTINED = 0 — baseline 정상 (PG 경로 미발생)"
fi

echo ""

# ---------------------------------------------------------------------------
# 정합 판정 게이트 — 대기하면 풀리는 판단 보류(미종결/미회수 선차감 기록/소비 적체)와
# 대기해도 안 풀리는 불일치(격리)를 가른다. 게이트를 통과해야 실제 값 비교로 넘어간다.
# ---------------------------------------------------------------------------

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ 재고 정합 교차검증 [3] — 상품별 redis 잔여 vs product RDB 잔여"
print_section "  productId=${PRODUCT_ID_BASE}..${PRODUCT_LAST_ID} (${PRODUCT_COUNT}종) / redis-stock=${REDIS_STOCK_CONTAINER}"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

echo ""

STOCK_VERDICT="SKIPPED"
STOCK_MISMATCH_COUNT=0
SETTLE_MISMATCH_COUNT=0

if [[ "${DB_QUARANTINED}" -gt 0 ]]; then
    print_warning "  ⚠️  격리 결제 잔류(QUARANTINED=${DB_QUARANTINED}) — 재고/건별 대조를 건너뛴다"
    STOCK_VERDICT="SKIPPED"
elif [[ "${DB_UNSETTLED}" -gt 0 ]] || [[ "${DB_NOISE}" -gt 0 ]] || [[ "${STOCK_COMMIT_LAG_PENDING}" == "true" ]]; then
    print_warning "  ⚠️  종결 대기 중 — 재고/건별 대조를 건너뛴다"
    echo "     미종결=${DB_UNSETTLED} / 미회수 선차감 기록=${DB_NOISE} / 소비 적체=${STOCK_COMMIT_LAG}(파티션수=${STOCK_COMMIT_PARTITIONS})"
    echo "     SETTLE_WAIT_SECONDS 를 늘려 재검증 필요:"
    echo "       SETTLE_WAIT_SECONDS=$(( SETTLE_WAIT_SECONDS * 2 )) CASE_NAME=${CASE_NAME} bash scripts/k6/verify-settlement.sh"
    STOCK_VERDICT="SKIPPED"
else
    print_info "  ✅ 선결 게이트 통과 — 미종결=0 / QUARANTINED=0 / 미회수 선차감 기록=0 / 소비 적체=0"
    echo ""

    # product RDB 잔여재고 — 대조 범위 상품 전부를 한 번의 질의로 가져온다
    RDB_STOCK_RAW=$(docker exec -i "${MYSQL_PRODUCT_CONTAINER}" mysql \
        -u "${MYSQL_PRODUCT_USER}" -p"${MYSQL_PRODUCT_PASSWORD}" \
        -D "${MYSQL_PRODUCT_DB}" -N -B -e \
        "SELECT product_id, quantity FROM stock WHERE product_id BETWEEN ${PRODUCT_ID_BASE} AND ${PRODUCT_LAST_ID} ORDER BY product_id;" \
        2>/dev/null) || {
        print_error "❌ product RDB 접속 실패 — 컨테이너(${MYSQL_PRODUCT_CONTAINER}) 확인"
        exit 1
    }

    # redis-stock 잔여재고 — 클러스터 리다이렉트 대응으로 -c 모드로 조회
    # (컨테이너 안에서 한 번의 exec로 전 상품을 순회해 호스트→컨테이너 프로세스 기동 비용을 줄인다)
    REDIS_STOCK_RAW=$(docker exec -i "${REDIS_STOCK_CONTAINER}" sh -c "
        for i in \$(seq 0 $((PRODUCT_COUNT - 1))); do
            id=\$((${PRODUCT_ID_BASE} + i))
            val=\$(redis-cli -c GET \"stock:{\${id}}\")
            if [ -z \"\${val}\" ]; then val=NIL; fi
            echo \"\${id} \${val}\"
        done
    ") || {
        print_error "❌ redis-stock 접속 실패 — 컨테이너(${REDIS_STOCK_CONTAINER}) 확인"
        exit 1
    }

    # 상품 id 기준으로 redis 잔여 vs RDB 잔여를 대조한다. 어긋난 상품만 개별로 뽑는다.
    STOCK_COMPARE=$(awk '
        NR==FNR { rdb[$1] = $2; next }
        {
            id = $1; redis_val = $2
            if (!(id in rdb)) {
                printf "%s\tMISSING_RDB_ROW\t%s\n", id, redis_val
                next
            }
            if (redis_val != rdb[id]) {
                printf "%s\t%s\t%s\n", id, rdb[id], redis_val
            }
        }
    ' <(echo "${RDB_STOCK_RAW}") <(echo "${REDIS_STOCK_RAW}"))

    if [[ -n "${STOCK_COMPARE}" ]]; then
        STOCK_MISMATCH_COUNT=$(echo "${STOCK_COMPARE}" | grep -c '.')
    fi

    if [[ "${STOCK_MISMATCH_COUNT}" -eq 0 ]]; then
        print_info "  ✅ [3] 재고 정합 PASS — ${PRODUCT_COUNT}종 전부 redis 잔여 == RDB 잔여"
        STOCK_VERDICT="PASS"
    else
        print_error "  ❌ [3] 재고 정합 FAIL — ${STOCK_MISMATCH_COUNT}종 어긋남 (productId / RDB / redis)"
        echo "${STOCK_COMPARE}" | while IFS=$'\t' read -r mismatch_id rdb_val redis_val; do
            echo "       productId=${mismatch_id}  RDB=${rdb_val}  redis=${redis_val}"
        done
        STOCK_VERDICT="FAIL"
    fi

    echo ""

    # ---------------------------------------------------------------------
    # 건별 대조 [4] — 종결된 결제의 선차감 기록 상태가 결제 상태와 부합하는지
    # DONE 이면 선차감 기록 전부 COMMITTED, FAILED 면 전부 REVERTED 여야 한다.
    # 총건수 교차식([1][2])은 유실만 잡고 개별 어긋남(같은 건수인데 다른 주문이 뒤바뀐
    # 경우 등)은 못 잡으므로 주문 단위로 직접 대조한다.
    # ---------------------------------------------------------------------

    print_section "▶ 건별 대조 [4] — 종결 결제 ↔ 선차감 기록 상태"

    SETTLE_MISMATCH_COUNT_RAW=$(mysql_payment_query \
        "SELECT COUNT(*) FROM payment_event pe
         JOIN stock_hold_record shr ON shr.order_id = pe.order_id
         WHERE (pe.status = 'DONE' AND shr.status <> 'COMMITTED')
            OR (pe.status = 'FAILED' AND shr.status <> 'REVERTED');") || {
        print_error "❌ DB 접속 실패 — 건별 대조 조회 실패"
        exit 1
    }
    SETTLE_MISMATCH_COUNT="${SETTLE_MISMATCH_COUNT_RAW:-0}"

    if [[ "${SETTLE_MISMATCH_COUNT}" -eq 0 ]]; then
        print_info "  ✅ [4] 건별 대조 PASS — 종결된 결제 전부 선차감 기록 상태와 부합"
    else
        print_error "  ❌ [4] 건별 대조 FAIL — ${SETTLE_MISMATCH_COUNT}건 어긋남 (order_id / payment_status / product_id / hold_status, 최대 20건)"
        SETTLE_MISMATCH_DETAIL=$(mysql_payment_query \
            "SELECT pe.order_id, pe.status, shr.product_id, shr.status
             FROM payment_event pe
             JOIN stock_hold_record shr ON shr.order_id = pe.order_id
             WHERE (pe.status = 'DONE' AND shr.status <> 'COMMITTED')
                OR (pe.status = 'FAILED' AND shr.status <> 'REVERTED')
             ORDER BY pe.order_id, shr.product_id
             LIMIT 20;")
        echo "${SETTLE_MISMATCH_DETAIL}" | while IFS=$'\t' read -r m_order m_status m_product m_hold; do
            echo "       order_id=${m_order}  status=${m_status}  productId=${m_product}  hold=${m_hold}"
        done
    fi
fi

echo ""

# ---------------------------------------------------------------------------
# 불일치 해석 가이드
# ---------------------------------------------------------------------------

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ 불일치 해석"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

echo ""

if [[ "${DB_UNSETTLED}" -gt 0 ]]; then
    print_error "  ❌ 미종결 잔여: READY=${DB_READY} / IN_PROGRESS=${DB_IN_PROGRESS} / RETRYING=${DB_RETRYING}"
    echo ""
    echo "  분류:"
    echo "    - e2e_timeout 건 중 settle 후 DONE 으로 전환된 경우"
    echo "      → 지연 종결 (k6 POLL_TIMEOUT_MS 이후 reconciler 가 회수해 처리)"
    echo "      → SETTLE_WAIT_SECONDS 를 늘려 재검증하면 DB_DONE 이 증가할 수 있음"
    echo ""
    echo "    - settle 대기 후에도 미종결인 경우"
    echo "      → 판단 보류 상태 — 계속 남으면 order_id 목록을 직접 확인:"
    echo "         docker exec -i ${MYSQL_CONTAINER} mysql \\"
    echo "           -u ${MYSQL_USER} -p${MYSQL_PASSWORD} \\"
    echo "           -D ${MYSQL_DB} -e \\"
    echo "           \"SELECT order_id, status, last_status_changed_at FROM payment_event"
    echo "             WHERE status IN ('READY','IN_PROGRESS','RETRYING')"
    echo "             ORDER BY last_status_changed_at DESC LIMIT 20;\""
    echo ""
else
    print_info "  ✅ 미종결 잔여 없음 (READY=0 / IN_PROGRESS=0 / RETRYING=0)"
fi

if [[ "${K6_TIMEOUT}" -gt 0 ]] && [[ "${CROSS_2_OK}" == "true" ]]; then
    print_warning "  ℹ️  e2e_timeout=${K6_TIMEOUT} 이지만 DB DONE 정합 — 지연 종결로 확인됨"
    echo "       (k6 폴링 타임아웃 이후 reconciler 가 정상 회수)"
elif [[ "${K6_TIMEOUT}" -gt 0 ]] && [[ "${CROSS_2_EXPLAINED}" == "true" ]]; then
    print_warning "  ℹ️  e2e_timeout=${K6_TIMEOUT} 이고 DB DONE 이 k6 관측보다 ${DIFF_2}건 많음 — 포기 건수 이내라 지연 종결로 설명됨"
    echo "       (관측이 잘린 것이지 유실이 아니다 — 총건수 교차식[1]과 상품별·건별 대조[3][4]가 유실을 잡는다)"
fi

echo ""

# ---------------------------------------------------------------------------
# 최종 판정 — 통과(0) / 판단 보류(2) / 불일치(3)
# ---------------------------------------------------------------------------

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ 최종 판정"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

echo ""
echo "  CASE_NAME:    ${CASE_NAME}"
echo "  settle 대기:  ${SETTLE_WAIT_SECONDS}s$( [[ "${SETTLE_WAIT_AUTO}" == "true" ]] && echo " (자동 산출)" || echo " (명시 지정)" )"
echo "  k6 결과:      confirm=${K6_CONFIRM} / DONE=${K6_DONE} / FAILED=${K6_FAILED} / timeout=${K6_TIMEOUT}"
echo "  DB 결과:      DONE=${DB_DONE} / FAILED=${DB_FAILED} / QUARANTINED=${DB_QUARANTINED} / 미종결=${DB_UNSETTLED}"
echo "  미회수 선차감 기록: ${DB_NOISE} / 소비 적체: ${STOCK_COMMIT_LAG}(파티션수=${STOCK_COMMIT_PARTITIONS}, 게이트 대기=${STOCK_COMMIT_LAG_PENDING})"
echo "  교차식 [1] k6총합==DB총합: $( [[ "${CROSS_1_OK}" == "true" ]] && echo PASS || echo FAIL )  (k6=${K6_TOTAL} / DB=${DB_TOTAL})"

CROSS_2_LABEL="FAIL"
if [[ "${CROSS_2_OK}" == "true" ]]; then
    CROSS_2_LABEL="PASS"
elif [[ "${CROSS_2_EXPLAINED}" == "true" ]]; then
    CROSS_2_LABEL="EXPLAINED(포기건수 이내)"
fi
echo "  교차식 [2] k6DONE==DB DONE: ${CROSS_2_LABEL}  (k6=${K6_DONE} / DB=${DB_DONE} / 차이=${DIFF_2} / timeout=${K6_TIMEOUT})"
echo "  교차식 [3] 상품별 재고 정합: ${STOCK_VERDICT}  (불일치 ${STOCK_MISMATCH_COUNT}종 / 대상 ${PRODUCT_COUNT}종)"

CROSS_4_LABEL="SKIPPED"
if [[ "${STOCK_VERDICT}" != "SKIPPED" ]]; then
    if [[ "${SETTLE_MISMATCH_COUNT}" -eq 0 ]]; then
        CROSS_4_LABEL="PASS"
    else
        CROSS_4_LABEL="FAIL"
    fi
fi
echo "  교차식 [4] 건별 대조:        ${CROSS_4_LABEL}  (불일치 ${SETTLE_MISMATCH_COUNT}건)"
echo ""

VERDICT="MISMATCH"
EXIT_CODE=3
VERDICT_REASON=""

if [[ "${DB_QUARANTINED}" -gt 0 ]]; then
    VERDICT="MISMATCH"
    EXIT_CODE=3
    VERDICT_REASON="격리 결제 잔류(QUARANTINED=${DB_QUARANTINED}) — 대기로 풀리지 않아 불일치로 낸다"
elif [[ "${DB_UNSETTLED}" -gt 0 ]] || [[ "${DB_NOISE}" -gt 0 ]] || [[ "${STOCK_COMMIT_LAG_PENDING}" == "true" ]]; then
    VERDICT="INCONCLUSIVE"
    EXIT_CODE=2
    VERDICT_REASON="종결 대기 중 — 미종결=${DB_UNSETTLED} 미회수 선차감 기록=${DB_NOISE} 소비 적체=${STOCK_COMMIT_LAG}(파티션수=${STOCK_COMMIT_PARTITIONS})"
elif [[ "${CROSS_1_OK}" != "true" ]] || ( [[ "${CROSS_2_OK}" != "true" ]] && [[ "${CROSS_2_EXPLAINED}" != "true" ]] ) || [[ "${STOCK_MISMATCH_COUNT}" -gt 0 ]] || [[ "${SETTLE_MISMATCH_COUNT}" -gt 0 ]]; then
    VERDICT="MISMATCH"
    EXIT_CODE=3
    VERDICT_REASON="정합 불일치 — 교차식1=${CROSS_1_OK} 교차식2=${CROSS_2_OK}(설명됨=${CROSS_2_EXPLAINED}, 차이=${DIFF_2}, timeout=${K6_TIMEOUT}) 재고불일치=${STOCK_MISMATCH_COUNT}종 건별대조불일치=${SETTLE_MISMATCH_COUNT}건"
elif [[ "${CROSS_2_OK}" != "true" ]]; then
    VERDICT="PASS"
    EXIT_CODE=0
    VERDICT_REASON="전항목 통과 — 교차식2는 k6 관측 포기 건수(${K6_TIMEOUT}) 이내 차이(${DIFF_2})로 설명됨(지연 종결). 상품 ${PRODUCT_COUNT}종 재고 정합 / 건별 대조 일치 / 격리·미종결·미회수 선차감 기록 0, 소비 적체 게이트(파티션 수 이하+더 줄지 않음) 통과"
else
    VERDICT="PASS"
    EXIT_CODE=0
    VERDICT_REASON="전항목 통과 — 상품 ${PRODUCT_COUNT}종 재고 정합 / 건별 대조 일치 / 격리·미종결·미회수 선차감 기록 0, 소비 적체 게이트(파티션 수 이하+더 줄지 않음) 통과"
fi

case "${VERDICT}" in
    PASS)
        print_info "✅ 통과(PASS) — ${VERDICT_REASON}"
        ;;
    INCONCLUSIVE)
        print_warning "⚠️  판단 보류(INCONCLUSIVE) — ${VERDICT_REASON}"
        echo "   대기 후 재실행하면 풀릴 수 있다:"
        echo "     SETTLE_WAIT_SECONDS=$(( SETTLE_WAIT_SECONDS * 2 )) CASE_NAME=${CASE_NAME} bash scripts/k6/verify-settlement.sh"
        ;;
    MISMATCH)
        print_error "❌ 불일치(MISMATCH) — ${VERDICT_REASON}"
        ;;
esac

echo ""

# 판정을 결과 파일에도 남긴다 (기계가 읽을 수 있는 형태). 부하 도구가 쓰는
# results/<CASE_NAME>.json 은 건드리지 않는다.
mkdir -p "${RESULTS_DIR}"
jq -n \
    --arg case_name "${CASE_NAME}" \
    --arg verdict "${VERDICT}" \
    --argjson exit_code "${EXIT_CODE}" \
    --arg reason "${VERDICT_REASON}" \
    --arg checked_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --argjson k6_total "${K6_TOTAL}" \
    --argjson k6_done "${K6_DONE}" \
    --argjson k6_timeout "${K6_TIMEOUT}" \
    --argjson db_total "${DB_TOTAL}" \
    --argjson db_done "${DB_DONE}" \
    --argjson cross_2_ok "$( [[ "${CROSS_2_OK}" == "true" ]] && echo true || echo false )" \
    --argjson cross_2_explained "$( [[ "${CROSS_2_EXPLAINED}" == "true" ]] && echo true || echo false )" \
    --argjson cross_2_diff "${DIFF_2}" \
    --argjson db_quarantined "${DB_QUARANTINED}" \
    --argjson db_unsettled "${DB_UNSETTLED}" \
    --argjson db_noise "${DB_NOISE}" \
    --argjson stock_commit_lag "${STOCK_COMMIT_LAG}" \
    --argjson stock_commit_partitions "${STOCK_COMMIT_PARTITIONS}" \
    --argjson stock_commit_lag_pending "$( [[ "${STOCK_COMMIT_LAG_PENDING}" == "true" ]] && echo true || echo false )" \
    --arg stock_verdict "${STOCK_VERDICT}" \
    --argjson stock_mismatch_count "${STOCK_MISMATCH_COUNT}" \
    --argjson settle_mismatch_count "${SETTLE_MISMATCH_COUNT}" \
    --argjson product_count "${PRODUCT_COUNT}" \
    --argjson product_id_base "${PRODUCT_ID_BASE}" \
    '{
        case_name: $case_name,
        verdict: $verdict,
        exit_code: $exit_code,
        reason: $reason,
        checked_at: $checked_at,
        counts: {
            k6_total: $k6_total,
            k6_done: $k6_done,
            k6_timeout: $k6_timeout,
            db_total: $db_total,
            db_done: $db_done,
            cross_2_ok: $cross_2_ok,
            cross_2_explained: $cross_2_explained,
            cross_2_diff: $cross_2_diff,
            db_quarantined: $db_quarantined,
            db_unsettled: $db_unsettled,
            db_noise: $db_noise,
            stock_commit_lag: $stock_commit_lag,
            stock_commit_partitions: $stock_commit_partitions,
            stock_commit_lag_pending: $stock_commit_lag_pending,
            stock_verdict: $stock_verdict,
            stock_mismatch_count: $stock_mismatch_count,
            settle_mismatch_count: $settle_mismatch_count,
            product_count: $product_count,
            product_id_base: $product_id_base
        }
    }' > "${VERDICT_JSON}"

print_info "  판정 기록: ${VERDICT_JSON}"

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

exit "${EXIT_CODE}"
