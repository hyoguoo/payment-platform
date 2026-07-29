#!/usr/bin/env bash
# 라이브 실측 동시 요청 구동 — 재고 경합과 중복 결제.
#
#   run-concurrent.sh --scenario stock-contention [--count 200] [--product-id 2] [--user-id 1]
#   run-concurrent.sh --scenario duplicate-payment [--idempotency-key <key>] [--product-id 2] [--user-id 1]
#
# 두 시나리오 모두 요청을 실제로 병렬 발사(백그라운드 서브셸)하고, 각 요청의 발사
# 시각(ms)을 기록해 얼마나 몰려서 나갔는지 출력한다. 이 출력이 동시성 증거다 —
# 순차 루프로는 이렇게 좁은 분포가 나오지 않는다.
#
# stock-contention: productId 상품에 대해 <count>건의 주문을 먼저 만든 뒤(순차 —
# 주문 생성은 재고에 영향이 없다, 차감은 승인 단계다), 그 주문들의 승인(confirm)을
# 동시에 쏜다. 재고 차감(Redis DECR)은 confirm 요청 자체가 동기로 처리하므로,
# HTTP 202(통과)/409(거절) 응답만으로 성공·거절을 바로 집계할 수 있다 — 최종 완료를
# 기다릴 필요가 없다.
#
# duplicate-payment: 같은 Idempotency-Key 로 체크아웃 2건을 동시에 쏜다. 체크아웃
# 응답 DTO 에는 중복 여부 필드가 없다 — 실제 신호는 HTTP 상태 코드다(신규 201 /
# 중복 200, PaymentController.checkout). 두 요청의 orderId 가 같고 한쪽만 201 이면
# 통과.
#
# 인자 없이 실행해도 경합 전용 상품(productId=2)을 기본으로 쓴다 — 기본 장면들이
# 쓰는 productId=1 은 이미 재고가 움직인 상태라 "정확히 100" 전제가 깨진다.
#
# 환경변수:
#   DRILL_BASE_URL  게이트웨이 주소 (기본: http://localhost:8090)
set -euo pipefail

BASE_URL="${DRILL_BASE_URL:-http://localhost:8090}"

scenario=""
count=200
product_id=2
user_id=1
idempotency_key=""

while [ $# -gt 0 ]; do
    case "$1" in
        --scenario) scenario="$2"; shift 2 ;;
        --count) count="$2"; shift 2 ;;
        --product-id) product_id="$2"; shift 2 ;;
        --user-id) user_id="$2"; shift 2 ;;
        --idempotency-key) idempotency_key="$2"; shift 2 ;;
        *)
            echo "알 수 없는 인자: $1" >&2
            exit 1
            ;;
    esac
done

if [ -z "$scenario" ]; then
    echo "usage: $0 --scenario <stock-contention|duplicate-payment> [옵션]" >&2
    exit 1
fi

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

now_ms() {
    python3 -c "import time;print(int(time.time()*1000))"
}

# set -euo pipefail 은 백그라운드 서브셸 안에서도 적용된다 — 요청이 비정상
# 종료하면 결과를 파일에 적기 전에 서브셸이 죽고, wait 는 그 실패를 부모에
# 전파하지 않는다. 그러면 결과 파일에 줄이 빠진 채로 집계가 그대로 진행돼
# "요청이 안 나간 것"과 "애플리케이션이 거절한 것"을 구분할 수 없다.
# 집계 직전에 줄 수를 기대 건수와 대조해, 유실이 있으면 그 집계를 판정
# 근거로 쓸 수 없다는 사실을 눈에 띄게 남긴다.
check_result_completeness() {
    results_file="$1"
    expected="$2"
    actual=$(wc -l < "$results_file" | tr -d ' ')
    if [ "$actual" -ne "$expected" ]; then
        missing=$((expected - actual))
        echo
        echo "!!! 경고: 결과 유실 — 기대 ${expected}건 중 ${actual}건만 기록됨 (${missing}건 유실) !!!" >&2
        echo "!!! 백그라운드 서브셸이 응답 기록 전에 죽었을 수 있다 — 이 실행의 집계는 판정 근거로 쓸 수 없다 !!!" >&2
    fi
}

run_stock_contention() {
    orders_file="$WORK_DIR/orders.txt"
    results_file="$WORK_DIR/results.txt"
    : > "$orders_file"
    : > "$results_file"

    echo "주문 생성: productId=$product_id 건수=$count (순차 — 재고에는 영향 없음)"
    i=1
    while [ "$i" -le "$count" ]; do
        response=$(curl -s -X POST "$BASE_URL/api/v1/payments/checkout" \
            -H "Content-Type: application/json" \
            -H "Idempotency-Key: drill-contention-$(date +%s)-$$-$i" \
            -d "{\"userId\":$user_id,\"gatewayType\":\"TOSS\",\"orderedProductList\":[{\"productId\":$product_id,\"quantity\":1}]}")
        order_id=$(echo "$response" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['orderId'])" 2>/dev/null || echo "")
        if [ -z "$order_id" ]; then
            echo "주문 생성 실패(i=$i) — 응답: $response" >&2
            exit 1
        fi
        echo "$order_id" >> "$orders_file"
        i=$((i + 1))
    done
    echo "주문 생성 완료: $(wc -l < "$orders_file" | tr -d ' ')건"

    orders=()
    while IFS= read -r line; do
        orders+=("$line")
    done < "$orders_file"

    echo "승인 동시 발사: ${#orders[@]}건"
    for order_id in "${orders[@]}"; do
        (
            fire_ms=$(now_ms)
            http_code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/payments/confirm" \
                -H "Content-Type: application/json" \
                -d "{\"userId\":$user_id,\"orderId\":\"$order_id\",\"amount\":1000.00,\"paymentKey\":\"fake-$order_id\",\"gatewayType\":\"TOSS\"}")
            echo "$fire_ms $http_code $order_id" >> "$results_file"
        ) &
    done
    wait

    check_result_completeness "$results_file" "${#orders[@]}"
    summarize_stock_contention "$results_file"
}

summarize_stock_contention() {
    results_file="$1"
    total=$(wc -l < "$results_file" | tr -d ' ')
    # 재고 부족 거절은 400 이다(E03013). 409 가 아니다 — 실측에서 확인했다.
    success=$(awk '$2==202' "$results_file" | wc -l | tr -d ' ')
    rejected=$(awk '$2==400' "$results_file" | wc -l | tr -d ' ')
    other=$((total - success - rejected))
    first_ms=$(awk '{print $1}' "$results_file" | sort -n | head -1)
    last_ms=$(awk '{print $1}' "$results_file" | sort -n | tail -1)
    spread_ms=$((last_ms - first_ms))

    echo
    echo "=== 발사 시각 분포 (동시성 증거) ==="
    echo "가장 이른 발사: ${first_ms}ms  가장 늦은 발사: ${last_ms}ms  전체 폭: ${spread_ms}ms"
    echo
    echo "=== 승인 응답 집계 ==="
    echo "총 요청: $total"
    echo "성공(202): $success"
    echo "거절(400, 재고 부족): $rejected"
    echo "기타: $other"
    # 기대에서 벗어난 응답이 있으면 무엇이었는지 바로 드러나야 한다 — "기타" 숫자만
    # 보고는 원인을 좇을 수 없다.
    if [ "$other" -gt 0 ]; then
        echo
        echo "--- 기타 응답 코드별 분포 ---"
        awk '$2!=202 && $2!=400 {print $2}' "$results_file" | sort | uniq -c | sort -rn
    fi
}

run_duplicate_payment() {
    key="${idempotency_key:-drill-dup-$(date +%s)}"
    results_file="$WORK_DIR/results.txt"
    : > "$results_file"

    echo "체크아웃 동시 발사: Idempotency-Key=$key"
    for _ in 1 2; do
        (
            fire_ms=$(now_ms)
            raw=$(curl -s -w '\n%{http_code}' -X POST "$BASE_URL/api/v1/payments/checkout" \
                -H "Content-Type: application/json" \
                -H "Idempotency-Key: $key" \
                -d "{\"userId\":$user_id,\"gatewayType\":\"TOSS\",\"orderedProductList\":[{\"productId\":$product_id,\"quantity\":1}]}")
            http_code=$(echo "$raw" | tail -n1)
            body=$(echo "$raw" | sed '$d')
            printf '%s|%s|%s\n' "$fire_ms" "$http_code" "$body" >> "$results_file"
        ) &
    done
    wait

    check_result_completeness "$results_file" 2
    summarize_duplicate_payment "$results_file"
}

summarize_duplicate_payment() {
    results_file="$1"
    echo
    echo "=== 발사 시각 분포 (동시성 증거) ==="
    sort -t'|' -k1,1n "$results_file" | while IFS='|' read -r ms code body; do
        echo "${ms}ms  http=$code  $body"
    done

    echo
    echo "=== 응답 대조 ==="
    python3 - "$results_file" <<'PYEOF'
import json
import sys

path = sys.argv[1]
rows = []
with open(path) as f:
    for line in f:
        ms, code, body = line.rstrip("\n").split("|", 2)
        rows.append((int(ms), int(code), json.loads(body)))
rows.sort(key=lambda r: r[0])

order_ids = {r[2]["data"]["orderId"] for r in rows}
codes = [r[1] for r in rows]

print(f"orderId 집합: {order_ids}")
print(f"orderId 동일 여부: {len(order_ids) == 1}")
print(f"HTTP 상태 코드: {codes}  (201=신규, 200=중복 — CheckoutResponse 에는 duplicate 필드가 없다)")
print(f"신규 1건 + 중복 1건: {sorted(codes) == [200, 201]}")
PYEOF
}

case "$scenario" in
    stock-contention) run_stock_contention ;;
    duplicate-payment) run_duplicate_payment ;;
    *)
        echo "알 수 없는 시나리오: $scenario (stock-contention|duplicate-payment 중 하나)" >&2
        exit 1
        ;;
esac
