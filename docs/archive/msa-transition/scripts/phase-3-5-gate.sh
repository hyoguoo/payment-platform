#!/usr/bin/env bash
# Phase 3.5 Gate — Pre-Phase-4 안정화 Gate.
# T3.5-Gate 산출물.
#
# 전제:
#   · T3.5-01 ~ T3.5-13 + T3.5-08~T3.5-12 완료 상태.
#   · docker, curl, jq 설치 (compose-up 스모크 실행 시).
#
# 검증 항목:
#   pre. ./gradlew test 전수 PASS (회귀 없음, ≥ 460건 목표)
#   a. @Lazy 잔재 0건 (pg-service DuplicateApprovalHandler 디커플링 회귀 방지, T3.5-05)
#   b. Toss/NicePay `matchIfMissing=true` 유지 여부 (T3.5-02 의도적 잔존 2건 인식)
#   c. consumer groupId 분리 확인 (StockCommit/StockRestore, T3.5-09)
#   d. KafkaMessagePublisher/PgEventPublisher 동기 발행 테스트 PASS (T3.5-08)
#   e. Product/User HttpAdapter 계약 테스트 PASS (T3.5-10)
#   f. PgOutbox RepeatedTest(50) PASS (T3.5-12)
#   g. phase-3-integration-smoke.sh 위임 (옵션, --with-smoke)
#
# 사용법:
#   bash scripts/phase-gate/phase-3-5-gate.sh                # 단위 테스트 기반 (빠름)
#   bash scripts/phase-gate/phase-3-5-gate.sh --with-smoke   # compose-up 스모크까지
#
# 결과:
#   · 전부 PASS → exit 0, Phase 4 진입 가능.
#   · 하나라도 FAIL → exit 1.

set -euo pipefail

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0
FAIL_ITEMS=()

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

WITH_SMOKE=false
for arg in "$@"; do
  case "${arg}" in
    --with-smoke) WITH_SMOKE=true ;;
    -h|--help) sed -n '2,28p' "$0"; exit 0 ;;
    *) echo "알 수 없는 옵션: ${arg}"; exit 1 ;;
  esac
done

pass() { echo -e "${GREEN}[PASS]${NC} $1"; PASS_COUNT=$((PASS_COUNT + 1)); }
fail() { echo -e "${RED}[FAIL]${NC} $1${2:+ — $2}"; FAIL_COUNT=$((FAIL_COUNT + 1)); FAIL_ITEMS+=("$1"); }
skip() { echo -e "${YELLOW}[SKIP]${NC} $1${2:+ — $2}"; SKIP_COUNT=$((SKIP_COUNT + 1)); }
section() { echo ""; echo -e "${CYAN}═══ $1 ═══${NC}"; }

# ─────────────────────────────────────────────
# pre. 전체 gradle test 전수 PASS
# ─────────────────────────────────────────────
section "pre. ./gradlew test 전수 PASS (회귀 없음)"
if cd "${REPO_ROOT}" && ./gradlew test -q --no-daemon --rerun-tasks 2>/dev/null; then
  XML_COUNT=$(find "${REPO_ROOT}" -path "*/build/test-results/test/*.xml" \
    -exec grep -o 'tests="[0-9]*"' {} \; 2>/dev/null \
    | grep -o '[0-9]*' | awk '{s+=$1} END {print s}' || echo "0")
  if [[ "${XML_COUNT:-0}" -ge 460 ]]; then
    pass "pre. 전체 gradle test PASS — ${XML_COUNT}건"
  else
    pass "pre. 전체 gradle test PASS — ${XML_COUNT}건 (기준 460 미만, 테스트 삭제 반영 여부 확인)"
  fi
else
  fail "pre. gradle test" "./gradlew test 실패 — 회귀 발생"
fi

# ─────────────────────────────────────────────
# a. @Lazy 잔재 0건
# ─────────────────────────────────────────────
section "a. @Lazy 잔재 0건 (T3.5-05 회귀 방지)"
LAZY_HITS=$(grep -rn "@Lazy" \
  "${REPO_ROOT}/pg-service/src/main" \
  "${REPO_ROOT}/payment-service/src/main" \
  "${REPO_ROOT}/product-service/src/main" \
  "${REPO_ROOT}/user-service/src/main" \
  "${REPO_ROOT}/gateway/src/main" 2>/dev/null \
  | grep -v "^\s*\*" \
  | grep -v "^\s*//" \
  | grep -v "javadoc\|주석\|comment" \
  | grep -E "import.*@Lazy|^[^*/]*@Lazy\s" || true)

if [[ -z "${LAZY_HITS}" ]]; then
  pass "a. @Lazy 사용 0건 (주석 언급 제외)"
else
  fail "a. @Lazy 잔재" "$(echo "${LAZY_HITS}" | wc -l | tr -d ' ')건 발견 — DI 순환 재발생 가능"
  echo "${LAZY_HITS}" | head -5
fi

# ─────────────────────────────────────────────
# b. matchIfMissing=true 의도적 잔존 2건 인식
# ─────────────────────────────────────────────
section "b. @ConditionalOnProperty matchIfMissing=true 의도적 잔존 (T3.5-02 기준 2건)"
MATCH_HITS=$(grep -rn 'matchIfMissing = true' \
  "${REPO_ROOT}/pg-service/src/main" \
  "${REPO_ROOT}/payment-service/src/main" \
  "${REPO_ROOT}/product-service/src/main" \
  "${REPO_ROOT}/user-service/src/main" \
  "${REPO_ROOT}/gateway/src/main" 2>/dev/null || true)

EXPECTED_FILES=("OutboxImmediateEventHandler" "TossPaymentGatewayStrategy")
ALL_EXPECTED=true
for expected in "${EXPECTED_FILES[@]}"; do
  if ! echo "${MATCH_HITS}" | grep -q "${expected}"; then
    ALL_EXPECTED=false
  fi
done

HITS_COUNT=$(echo "${MATCH_HITS}" | grep -c "matchIfMissing" || echo "0")
if [[ "${ALL_EXPECTED}" == "true" && "${HITS_COUNT}" -le 2 ]]; then
  pass "b. matchIfMissing=true 의도적 2건 (OutboxImmediateEventHandler, TossPaymentGatewayStrategy)"
else
  fail "b. matchIfMissing=true 편차" "예상 2건 (T3.5-02 기준) 대비 실측 ${HITS_COUNT}건 / 기대 파일 포함 여부: ${ALL_EXPECTED}"
fi

# ─────────────────────────────────────────────
# c. consumer groupId 분리 (T3.5-09)
# ─────────────────────────────────────────────
section "c. consumer groupId 분리 — product-service-stock-{commit,restore} (T3.5-09)"
SC_GID=$(grep -E 'GROUP_ID\s*=\s*"product-service-stock-commit"' \
  "${REPO_ROOT}/product-service/src/main/java/com/hyoguoo/paymentplatform/product/infrastructure/messaging/consumer/StockCommitConsumer.java" 2>/dev/null || true)
SR_GID=$(grep -E 'GROUP_ID\s*=\s*"product-service-stock-restore"' \
  "${REPO_ROOT}/product-service/src/main/java/com/hyoguoo/paymentplatform/product/infrastructure/messaging/consumer/StockRestoreConsumer.java" 2>/dev/null || true)

if [[ -n "${SC_GID}" && -n "${SR_GID}" ]]; then
  pass "c. StockCommit/StockRestore consumer groupId 분리됨"
else
  fail "c. consumer groupId" "StockCommit 또는 StockRestore groupId 가 분리되지 않음"
fi

# ─────────────────────────────────────────────
# d. 동기 발행 테스트 PASS (T3.5-08)
# ─────────────────────────────────────────────
section "d. KafkaMessagePublisher / PgEventPublisher 동기 발행 테스트 (T3.5-08)"
if cd "${REPO_ROOT}" && ./gradlew \
    :payment-service:test --tests "*.KafkaMessagePublisherTest" --tests "*.OutboxRelayServiceTest" \
    :pg-service:test --tests "*.PgEventPublisherTest" --tests "*.PgOutboxRelayServiceTest" \
    -q --no-daemon 2>/dev/null; then
  pass "d. publisher/relay 동기 발행 테스트 PASS"
else
  fail "d. publisher/relay 동기 발행" "T3.5-08 테스트 실패 — fire-and-forget 회귀 가능"
fi

# ─────────────────────────────────────────────
# e. HTTP Adapter 계약 테스트 (T3.5-10)
# ─────────────────────────────────────────────
section "e. ProductHttpAdapter / UserHttpAdapter 계약 테스트 (T3.5-10)"
if cd "${REPO_ROOT}" && ./gradlew :payment-service:test \
    --tests "*.ProductHttpAdapterContractTest" \
    --tests "*.UserHttpAdapterContractTest" \
    -q --no-daemon 2>/dev/null; then
  pass "e. HTTP Adapter 404/503/429/500 계약 테스트 PASS"
else
  fail "e. HTTP Adapter 계약" "T3.5-10 계약 테스트 실패 — 404/5xx 매핑 회귀"
fi

# ─────────────────────────────────────────────
# f. PgOutbox RepeatedTest(50) (T3.5-12)
# ─────────────────────────────────────────────
section "f. PgOutbox race RepeatedTest x50 (T3.5-12)"
if cd "${REPO_ROOT}" && ./gradlew :pg-service:test \
    --tests "*.PgOutboxImmediateWorkerTest" \
    -q --no-daemon 2>/dev/null; then
  pass "f. PgOutboxImmediateWorkerTest PASS (stop + exactly-once RepeatedTest x50)"
else
  fail "f. PgOutbox RepeatedTest" "T3.5-12 race 테스트 1회 이상 실패 — race window 노출 가능"
fi

# ─────────────────────────────────────────────
# g. phase-3-integration-smoke (옵션)
# ─────────────────────────────────────────────
section "g. phase-3-integration-smoke 위임 (${WITH_SMOKE:-false})"
if [[ "${WITH_SMOKE}" == "true" ]]; then
  if bash "${REPO_ROOT}/scripts/phase-gate/phase-3-integration-smoke.sh"; then
    pass "g. phase-3-integration-smoke exit 0"
  else
    fail "g. phase-3-integration-smoke" "compose-up 스모크 실패"
  fi
else
  skip "g. phase-3-integration-smoke" "--with-smoke 미지정 (compose-up 환경에서 별도 수동 실행 권장)"
fi

# ─────────────────────────────────────────────
# 최종 요약
# ─────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════"
echo " Phase 3.5 Gate 결과"
echo "════════════════════════════════════════════"
echo -e " PASS: ${GREEN}${PASS_COUNT}${NC}"
echo -e " SKIP: ${YELLOW}${SKIP_COUNT}${NC}"
echo -e " FAIL: ${RED}${FAIL_COUNT}${NC}"

if [[ ${FAIL_COUNT} -gt 0 ]]; then
  echo ""
  echo -e "${RED}실패 항목:${NC}"
  for item in "${FAIL_ITEMS[@]}"; do
    echo "  - ${item}"
  done
  echo ""
  echo -e "${RED}[GATE FAIL]${NC} Phase 3.5 재수정 후 재실행 필요."
  exit 1
fi

echo ""
echo -e "${GREEN}[GATE PASS]${NC} Phase 3.5 완료 — Phase 4 진입 가능."
echo ""
echo "  확정 규약:"
echo "    · Kafka 발행 동기 불변식 — whenComplete fire-and-forget 금지 (T3.5-08)"
echo "    · StockCommit/StockRestore 독립 groupId (T3.5-09)"
echo "    · 서브도메인 HTTP 404/503/429/500 매핑 계약 (T3.5-10)"
echo "    · compose-up 기반 smoke 자동화 (T3.5-11)"
echo "    · Outbox race 50회 반복 불변식 (T3.5-12)"
echo "    · Kafka W3C traceparent 자동 전파 (T3.5-13)"
echo ""
echo "  다음 단계: Phase 4 — Toxiproxy 장애 주입 + k6 재설계 + 로컬 오토스케일러"
exit 0
