#!/usr/bin/env bash
# verify-settlement.sh — settle 대기 후 DB 결제 종결 분포 ↔ k6 카운트 교차 검증
#                        + payment_history e2e 처리 시각 + 재고 정합 교차검증
#
# 용도:
#   run-benchmark.sh 완료 후 실행. reconciler settle 대기(최악 scan 1주기 상한)를
#   마친 뒤 payment_event status별 DB 카운트와 k6 결과 JSON 카운트를 교차한다.
#   settle 종결 후 redis-stock 잔여재고 vs product RDB 차감 합 교차검증도 수행한다.
#
#   교차식:
#     [1] k6(DONE + FAILED + timeout) == DB(DONE + FAILED + QUARANTINED + 미종결)
#     [2] k6(DONE)                    == DB(DONE)
#     [3] 재고 정합: 미종결=0 AND QUARANTINED=0 선결 후 redis 잔여 == RDB 잔여
#
#   불일치 해석:
#     - e2e_timeout 중 settle 후 DONE → 지연 종결(k6 타임아웃 내 미도달했으나 후속 settle)
#     - settle 후 미종결(READY/IN_PROGRESS/RETRYING) → 진짜 유실(silent loss) 후보
#     - QUARANTINED > 0 → baseline(failRate=0)에서 발생 불가 → redis-stock 헬스 의심
#
# settle 대기 계산 (SETTLE_WAIT_SECONDS 미지정 시 자동 산출):
#   RECONCILER_TIMEOUT + ceil(RECONCILER_SCAN_MS / 1000) + 여유(12s)
#   예) RECONCILER_TIMEOUT=30, RECONCILER_SCAN_MS=15000 → 30 + 15 + 12 = 57s
#   예) RECONCILER_TIMEOUT=600, RECONCILER_SCAN_MS=15000 → 600 + 15 + 12 = 627s
#
# 재고 정합식 상세:
#   - redis-stock 잔여 = 초기시드 − DECR 누계(확인요청) + INCR 누계(FAILED·pg보상)
#     → 종결 완료(미종결=0) AND QUARANTINED=0 상태에서만 RDB 잔여와 등식 성립
#   - 미종결 ≥ 1 이면 아직 INCR 미복원 가능 → inconclusive (단독 PASS 금지)
#   - QUARANTINED > 0 이면 AMOUNT_MISMATCH(redis −1 미보상)·CACHE_DOWN(net zero)
#     사유별로 redis↔RDB 관계가 달라지므로 단일 등식 적용 불가 → inconclusive
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
#   REDIS_STOCK_CONTAINER   — redis-stock 컨테이너명 (기본: payment-redis-stock)
#   PRODUCT_ID              — 재고 교차검증 대상 product id (기본: 1)
#
# 선행 조건:
#   - run-benchmark.sh 완료 (results/<CASE_NAME>.json 존재)
#   - benchmark compose 스택 기동 중
#   - jq 설치 (JSON 파싱)
#
# 종료 코드:
#   0 — 교차 검증 완료 (불일치 존재 시에도 0 — 결과는 출력으로 확인)
#   1 — 선행 조건 미충족 또는 DB 접속 실패

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
PRODUCT_ID="${PRODUCT_ID:-1}"

RESULT_JSON="${RESULTS_DIR}/${CASE_NAME}.json"

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
DB_RAW=$(docker exec -i "${MYSQL_CONTAINER}" mysql \
    -u "${MYSQL_USER}" -p"${MYSQL_PASSWORD}" \
    -D "${MYSQL_DB}" -N -B -e \
    "SELECT status, COUNT(*) FROM payment_event GROUP BY status ORDER BY status;" \
    2>/dev/null) || {
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

# 미종결(READY/IN_PROGRESS/RETRYING) — settle 후에도 남아있으면 silent loss 후보
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
# payment_history 기반 e2e 처리 시각 산출 (D4 — 처리 계측)
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
HISTORY_RAW=$(docker exec -i "${MYSQL_CONTAINER}" mysql \
    -u "${MYSQL_USER}" -p"${MYSQL_PASSWORD}" \
    -D "${MYSQL_DB}" -N -B -e \
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
     ) sub;" \
    2>/dev/null) || {
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
# redis-stock 헬스 확인 (QUARANTINED 트리아지용 + 재고 교차검증 전제)
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

# [2] k6 DONE == DB DONE
print_section "  [2] k6(DONE) == DB(DONE)"
echo "      k6 DONE: ${K6_DONE}  /  DB DONE: ${DB_DONE}"

CROSS_2_OK=false
if [[ "${K6_DONE}" -eq "${DB_DONE}" ]]; then
    CROSS_2_OK=true
    print_info "      ✅ 일치 — DONE 정합"
else
    DIFF_2=$(( K6_DONE - DB_DONE ))
    print_warning "      ⚠️  불일치 (k6 - DB = ${DIFF_2})"
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
    print_error "  ❌ QUARANTINED = ${DB_QUARANTINED} — baseline(failRate=0)에서 PG 경로 발생 불가"
    echo ""
    echo "  트리아지 절차:"
    echo "    1. redis-stock 헬스 확인"
    echo "       컨테이너: ${REDIS_STOCK_CONTAINER} / 현재 상태: ${REDIS_STOCK_HEALTH}"
    echo "       명령: docker exec ${REDIS_STOCK_CONTAINER} redis-cli ping"
    echo "       stock:{1} 값: $(docker exec -i "${REDIS_STOCK_CONTAINER}" redis-cli GET "stock:{1}" 2>/dev/null || echo 'ERROR')"
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
# 재고 정합 교차검증 [3] — redis 잔여 vs product RDB 잔여
# ---------------------------------------------------------------------------
# 정합 선결 게이트(AND 결합):
#   gate_a: 미종결(DB_UNSETTLED) == 0  — 아직 INCR 미복원 가능성 없음
#   gate_b: QUARANTINED          == 0  — AMOUNT_MISMATCH/CACHE_DOWN 등 사유별 예외 없음
#
# 두 게이트 모두 통과 시에만 단일 등식 적용:
#   redis 잔여 == product RDB 잔여
#   (RDB 잔여 = stock.quantity,  redis 잔여 = stock:{PRODUCT_ID} GET)
#
# QUARANTINED > 0 이면:
#   - AMOUNT_MISMATCH: redis −1 미보상 → redis < RDB 잔여
#   - CACHE_DOWN:      net zero(보상 INCR 없이 DECR 취소) → redis == RDB 가능하나 경로 불명확
#   단일 등식으로 합부 판정 불가 → inconclusive 처리.
# ---------------------------------------------------------------------------

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ 재고 정합 교차검증 [3] — redis 잔여 vs product RDB 잔여"
print_section "  productId=${PRODUCT_ID} / redis-stock=${REDIS_STOCK_CONTAINER}"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

echo ""

STOCK_VERDICT="INCONCLUSIVE"

# 선결 게이트 검사
if [[ "${DB_UNSETTLED}" -gt 0 ]]; then
    print_warning "  ⚠️  게이트 미충족: 미종결 ${DB_UNSETTLED}건 잔여 (INCR 미복원 가능)"
    print_warning "     → inconclusive (단독 PASS 금지)"
    echo "     SETTLE_WAIT_SECONDS 를 늘려 재검증 필요:"
    echo "       SETTLE_WAIT_SECONDS=$(( SETTLE_WAIT_SECONDS * 2 )) CASE_NAME=${CASE_NAME} bash scripts/k6/verify-settlement.sh"
elif [[ "${DB_QUARANTINED}" -gt 0 ]]; then
    print_warning "  ⚠️  게이트 미충족: QUARANTINED ${DB_QUARANTINED}건 — 사유별 redis↔RDB 관계 불명확"
    print_warning "     → inconclusive (단일 등식 적용 불가)"
    echo ""
    echo "  QUARANTINED 사유별 redis↔RDB 관계:"
    echo "    AMOUNT_MISMATCH: redis −1 미보상 → redis 잔여 < RDB 잔여 (DECR 미복원)"
    echo "    CACHE_DOWN:      redis DECR 미수행 → redis 잔여 == RDB 잔여 가능하나 경로 불명확"
    echo "  → 트리아지 후 재측정 필요 (위 QUARANTINED 섹션 참고)"
else
    # 두 게이트 모두 통과 → 실제 값 비교
    print_info "  ✅ 선결 게이트 통과: 미종결=0 / QUARANTINED=0"
    echo ""

    # redis 잔여재고 조회
    REDIS_STOCK_VAL=$(docker exec -i "${REDIS_STOCK_CONTAINER}" redis-cli GET "stock:{${PRODUCT_ID}}" 2>/dev/null || echo "ERROR")

    # product RDB 잔여재고 조회
    RDB_STOCK_VAL=$(docker exec -i "${MYSQL_PRODUCT_CONTAINER}" mysql \
        -u "${MYSQL_PRODUCT_USER}" -p"${MYSQL_PRODUCT_PASSWORD}" \
        -D "${MYSQL_PRODUCT_DB}" -N -B -e \
        "SELECT quantity FROM stock WHERE product_id = ${PRODUCT_ID};" \
        2>/dev/null | tail -1) || RDB_STOCK_VAL="ERROR"

    echo "  redis stock:{${PRODUCT_ID}}  = ${REDIS_STOCK_VAL}"
    echo "  RDB stock.quantity        = ${RDB_STOCK_VAL}"
    echo ""

    if [[ "${REDIS_STOCK_VAL}" == "ERROR" ]]; then
        print_warning "  ⚠️  redis-stock 조회 실패 → inconclusive"
        STOCK_VERDICT="INCONCLUSIVE"
    elif [[ "${RDB_STOCK_VAL}" == "ERROR" || -z "${RDB_STOCK_VAL}" ]]; then
        print_warning "  ⚠️  product RDB 조회 실패 → inconclusive"
        STOCK_VERDICT="INCONCLUSIVE"
    elif [[ "${REDIS_STOCK_VAL}" -eq "${RDB_STOCK_VAL}" ]]; then
        print_info "  ✅ [3] 재고 정합 PASS — redis 잔여 == RDB 잔여 (${REDIS_STOCK_VAL})"
        STOCK_VERDICT="PASS"
    else
        STOCK_DIFF=$(( REDIS_STOCK_VAL - RDB_STOCK_VAL ))
        print_error "  ❌ [3] 재고 정합 FAIL — redis ${REDIS_STOCK_VAL} vs RDB ${RDB_STOCK_VAL} (차이 ${STOCK_DIFF})"
        print_error "     → redis DECR/INCR 미정산 또는 보상 누락 가능성"
        STOCK_VERDICT="FAIL"
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
    echo "      → 진짜 유실(silent loss) 후보"
    echo "      → payment_event order_id 목록 확인:"
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
fi

echo ""

# ---------------------------------------------------------------------------
# 최종 요약
# ---------------------------------------------------------------------------

print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
print_section "▶ 최종 요약"
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

echo ""
echo "  CASE_NAME:    ${CASE_NAME}"
echo "  settle 대기:  ${SETTLE_WAIT_SECONDS}s$( [[ "${SETTLE_WAIT_AUTO}" == "true" ]] && echo " (자동 산출)" || echo " (명시 지정)" )"
echo "  k6 결과:      confirm=${K6_CONFIRM} / DONE=${K6_DONE} / FAILED=${K6_FAILED} / timeout=${K6_TIMEOUT}"
echo "  DB 결과:      DONE=${DB_DONE} / FAILED=${DB_FAILED} / QUARANTINED=${DB_QUARANTINED} / 미종결=${DB_UNSETTLED}"
echo "  redis-stock:  ${REDIS_STOCK_HEALTH}"
echo ""

VERDICT_TOTAL="FAIL"
VERDICT_DONE="FAIL"
[[ "${CROSS_1_OK}" == "true" ]] && VERDICT_TOTAL="PASS"
[[ "${CROSS_2_OK}" == "true" ]] && VERDICT_DONE="PASS"

echo "  교차식 [1] k6총합 == DB총합:  ${VERDICT_TOTAL}  (k6=${K6_TOTAL} / DB=${DB_TOTAL})"
echo "  교차식 [2] k6DONE == DB DONE:  ${VERDICT_DONE}   (k6=${K6_DONE} / DB=${DB_DONE})"
echo "  교차식 [3] 재고 정합(redis==RDB): ${STOCK_VERDICT:-INCONCLUSIVE}  (productId=${PRODUCT_ID})"
echo ""

# 종합 판정
# - [1][2] PASS + QUARANTINED=0 + [3] PASS → 전항목 통과
# - [3] INCONCLUSIVE → 정산 미완 또는 QUARANTINED 잔여 → 재측정 필요
# - [3] FAIL → 재고 정합 위반
if [[ "${CROSS_1_OK}" == "true" ]] && [[ "${CROSS_2_OK}" == "true" ]] && [[ "${DB_QUARANTINED}" -eq 0 ]] && [[ "${STOCK_VERDICT}" == "PASS" ]]; then
    print_info "✅ 교차 검증 통과 — 결제 + 재고 정합 확인"
    echo "   silent loss 없음 / QUARANTINED 없음 / 총건수 일치 / 재고 정합"
elif [[ "${STOCK_VERDICT}" == "INCONCLUSIVE" ]] && [[ "${CROSS_1_OK}" == "true" ]] && [[ "${CROSS_2_OK}" == "true" ]]; then
    print_warning "⚠️  결제 정합 PASS — 재고 교차검증 inconclusive (선결 게이트 미충족)"
    echo "   재고 판정은 미종결=0 AND QUARANTINED=0 선결 후 재실행 필요"
    if [[ "${DB_UNSETTLED}" -gt 0 ]]; then
        echo "   → SETTLE_WAIT_SECONDS 를 늘려 재검증:"
        echo "      SETTLE_WAIT_SECONDS=$(( SETTLE_WAIT_SECONDS * 2 )) CASE_NAME=${CASE_NAME} bash scripts/k6/verify-settlement.sh"
    fi
else
    print_warning "⚠️  교차 검증 불일치 항목 있음 — 위 해석 가이드를 참고하세요."
    if [[ "${DB_UNSETTLED}" -gt 0 ]]; then
        echo "   → SETTLE_WAIT_SECONDS 를 늘려 재검증:"
        echo "      SETTLE_WAIT_SECONDS=120 CASE_NAME=${CASE_NAME} bash scripts/k6/verify-settlement.sh"
    fi
fi

echo ""
print_section "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
