#!/usr/bin/env bash
# 라이브 실측 결제 구동 — 시나리오 하나를 끝까지 태운다.
#
#   run-scenario.sh <시나리오> [userId] [productId]
#
#   run-scenario.sh success
#   run-scenario.sh fail
#   run-scenario.sh retry
#   run-scenario.sh flaky 1 1
#
# 시나리오는 결제 키 접두어로 벤더 응답을 고른다 (references/scenarios.md 참고).
#
#   시나리오   접두어         벤더 응답                  최종 상태
#   success    fake-          첫 호출 성공                완료
#   fail       fake-fail-     확정 실패                   실패(+보상)
#   retry      fake-retry-    매 호출 재시도 가능 실패     시도 소진 → 격리
#   flaky      fake-flaky-    두 번 실패 후 세 번째 승인   재시도 자가 회복
#
# 주문 생성(checkout) → 승인(confirm) → 상태(status) 순서로 호출하고 각 단계 응답을
# 그대로 출력한다. confirm 이후에는 /status 를 폴링해 DONE/FAILED 로 끝나는 것을
# 기다린다 — success/fail 은 몇 초 안에 끝난다. retry/flaky 는 재시도 간격 때문에
# 더 걸리고, retry 는 격리가 고객 응대 상태에는 드러나지 않아(PaymentStatusResponse 에
# QUARANTINED 가 없다 — PROCESSING 로 남는다) 폴링이 타임아웃으로 끝나는 것이 정상이다.
# 격리 확정 자체는 관리자 화면의 확정 시도 이력 카드로 확인한다(scenarios.md 장면 3).
#
# 환경변수:
#   DRILL_BASE_URL      게이트웨이 주소 (기본: http://localhost:8090)
#   DRILL_POLL_TIMEOUT  /status 폴링 최대 대기 초 (기본 90)
#   DRILL_POLL_INTERVAL /status 폴링 간격 초 (기본 2)
set -euo pipefail

BASE_URL="${DRILL_BASE_URL:-http://localhost:8090}"
POLL_TIMEOUT="${DRILL_POLL_TIMEOUT:-90}"
POLL_INTERVAL="${DRILL_POLL_INTERVAL:-2}"

if [ $# -lt 1 ]; then
    echo "usage: $0 <success|fail|retry|flaky> [userId] [productId]" >&2
    exit 1
fi

scenario="$1"
user_id="${2:-1}"
product_id="${3:-1}"

case "$scenario" in
    success) prefix="fake-" ;;
    fail)    prefix="fake-fail-" ;;
    retry)   prefix="fake-retry-" ;;
    flaky)   prefix="fake-flaky-" ;;
    *)
        echo "알 수 없는 시나리오: $scenario (success|fail|retry|flaky 중 하나)" >&2
        exit 1
        ;;
esac

echo "=== 시나리오: $scenario (접두어 ${prefix}) userId=$user_id productId=$product_id ==="

echo "--- checkout ---"
checkout_response=$(curl -s -X POST "$BASE_URL/api/v1/payments/checkout" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: drill-$(date +%s)-$$" \
    -d "{\"userId\":$user_id,\"gatewayType\":\"TOSS\",\"orderedProductList\":[{\"productId\":$product_id,\"quantity\":1}]}")
echo "$checkout_response"

order_id=$(echo "$checkout_response" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['orderId'])" 2>/dev/null || echo "")
if [ -z "$order_id" ]; then
    echo "checkout 응답에서 orderId 를 못 읽었다 — 위 응답을 확인한다" >&2
    exit 1
fi
echo "orderId=$order_id"

echo "--- confirm ---"
confirm_response=$(curl -s -X POST "$BASE_URL/api/v1/payments/confirm" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":$user_id,\"orderId\":\"$order_id\",\"amount\":1000.00,\"paymentKey\":\"${prefix}${order_id}\",\"gatewayType\":\"TOSS\"}")
echo "$confirm_response"

echo "--- status (최대 ${POLL_TIMEOUT}초 폴링, ${POLL_INTERVAL}초 간격) ---"
elapsed=0
last_status=""
while [ "$elapsed" -lt "$POLL_TIMEOUT" ]; do
    status_response=$(curl -s "$BASE_URL/api/v1/payments/$order_id/status")
    status=$(echo "$status_response" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['status'])" 2>/dev/null || echo "UNKNOWN")
    if [ "$status" != "$last_status" ]; then
        echo "$(date '+%H:%M:%S')  status=$status"
        last_status="$status"
    fi
    if [ "$status" = "DONE" ] || [ "$status" = "FAILED" ]; then
        break
    fi
    sleep "$POLL_INTERVAL"
    elapsed=$((elapsed + POLL_INTERVAL))
done

echo "--- 최종 ---"
echo "orderId=$order_id  status=$last_status"
if [ "$last_status" != "DONE" ] && [ "$last_status" != "FAILED" ]; then
    echo "폴링 타임아웃 — $last_status 로 남음(재시도/격리 시나리오는 정상, 관리자 화면에서 확인한다)" >&2
fi
