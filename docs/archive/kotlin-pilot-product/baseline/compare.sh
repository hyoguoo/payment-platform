#!/usr/bin/env bash
# compare.sh — product-service 이관 전후 동작 비교(BRIEF 판정 4).
#
# 띄워 둔 로컬 스택(bash scripts/compose-up.sh --mode fake)에 같은 시나리오를 돌려 결과를 <out-dir>에 남긴다.
# 실행마다 달라지는 값(주문 번호, 시각)은 결과에서 지우고, 이관 전 결과 디렉토리와 diff 해서 비교한다.
#
# 사용법:
#   bash docs/archive/kotlin-pilot-product/baseline/compare.sh docs/archive/kotlin-pilot-product/baseline/java
#   bash docs/archive/kotlin-pilot-product/baseline/compare.sh docs/archive/kotlin-pilot-product/baseline/kotlin
#   diff -r -x smoke.log docs/archive/kotlin-pilot-product/baseline/java docs/archive/kotlin-pilot-product/baseline/kotlin
#   (smoke.log 는 실행마다 다른 trace ID·시각이 섞인 원문이라 비교에서 뺀다)
#
# 시나리오:
#   1. gateway 경유 상품 단건·목록·없는 상품 조회 (응답 본문 그대로)
#   2. 결제 1건(fake PG) 뒤 상품 1 재고가 1 줄어드는가
#   3. 같은 확정 메시지를 두 번 보내면 재고가 한 번만 주는가
#   4. 재고보다 큰 수량의 확정 메시지가 격리 토픽으로 가는가(키, 재고 유지)
#   5. smoke-all.sh --with-trace 단계별 통과 여부
set -uo pipefail

OUT="${1:?결과 디렉토리가 필요하다}"
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
GW="${GATEWAY_BASE:-http://localhost:8090}"
MYSQL_CONTAINER="${MYSQL_PRODUCT_CONTAINER:-payment-mysql-product}"
MYSQL_PASSWORD="${MYSQL_PRODUCT_PASSWORD:-payment123}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-payment-kafka}"
TOPIC="payment.events.stock-committed"
DLQ_TOPIC="payment.events.stock-committed.dlq"
READ_PRODUCT=9901
MSG_PRODUCT=9902
RUN_TAG="cmp-$(date +%s)"

mkdir -p "$OUT"
RESULTS="$OUT/results.txt"
: > "$RESULTS"

sql() {
    docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_PASSWORD" -D product -N -B -e "$1" 2>/dev/null
}

stock_of() {
    sql "SELECT quantity FROM stock WHERE product_id = $1"
}

record() {
    echo "$1" | tee -a "$RESULTS"
}

produce() {
    # $1 = Kafka 키, $2 = 값
    printf '%s|%s\n' "$1" "$2" | docker exec -i "$KAFKA_CONTAINER" kafka-console-producer \
        --bootstrap-server localhost:9092 --topic "$TOPIC" \
        --property parse.key=true --property key.separator='|' >/dev/null 2>&1
}

wait_stock() {
    # $1 = 상품, $2 = 기대 재고, $3 = 제한 초
    local deadline=$(( $(date +%s) + $3 ))
    while (( $(date +%s) < deadline )); do
        [ "$(stock_of "$1")" = "$2" ] && return 0
        sleep 1
    done
    return 1
}

message() {
    # $1 = 상품, $2 = 수량, $3 = 멱등 키, $4 = 주문 번호
    # 만료 시각은 정상 발행과 같이 지금 + 8일로 둔다. 과거 시각이면 중복 판정 기록이 만료로 지워져
    # 두 번째 메시지도 새 메시지로 처리된다(JdbcEventDedupeStore.recordIfAbsent).
    printf '{"productId":%s,"qty":%s,"idempotencyKey":"%s","occurredAt":"%s","orderId":"%s","expiresAt":"%s"}' \
        "$1" "$2" "$3" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$4" "$(date -u -v+8d +%Y-%m-%dT%H:%M:%SZ)"
}

# ── 준비: 비교용 상품 두 개를 고정 재고로 심는다 ──────────────────────────────
sql "INSERT IGNORE INTO product (id, name, price, description, seller_id) VALUES
     ($READ_PRODUCT, 'Compare Read', 1234.50, 'compare', 3),
     ($MSG_PRODUCT, 'Compare Message', 1000.00, 'compare', 3);
     INSERT INTO stock (product_id, quantity) VALUES ($READ_PRODUCT, 77), ($MSG_PRODUCT, 10)
     ON DUPLICATE KEY UPDATE quantity = VALUES(quantity);"

# ── 1. 조회 ───────────────────────────────────────────────────────────────
curl -sS -o "$OUT/get-one.json" -w '%{http_code}\n' "$GW/api/v1/products/$READ_PRODUCT" > "$OUT/get-one.status"
curl -sS -o "$OUT/get-missing.json" -w '%{http_code}\n' "$GW/api/v1/products/999999" > "$OUT/get-missing.status"
# 목록은 결제로 재고가 바뀌는 상품 1의 stock 값만 지우고 비교한다.
curl -sS "$GW/api/v1/products?page=0&size=100" \
    | jq -c 'del(.content[] | select(.id == 1) | .stock)' > "$OUT/get-list.json"
record "get-one status=$(cat "$OUT/get-one.status") get-missing status=$(cat "$OUT/get-missing.status")"

# ── 2. 결제 1건 뒤 재고 확정 ─────────────────────────────────────────────────
before=$(stock_of 1)
checkout=$(curl -sS -X POST "$GW/api/v1/payments/checkout" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $RUN_TAG-checkout" \
    -d '{"userId":1,"gatewayType":"TOSS","orderedProductList":[{"productId":1,"quantity":1}]}')
order_id=$(echo "$checkout" | jq -r '(.data // .) | .orderId')
amount=$(echo "$checkout" | jq -r '(.data // .) | .totalAmount')
confirm_status=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$GW/api/v1/payments/confirm" \
    -H 'Content-Type: application/json' \
    -d "{\"userId\":1,\"orderId\":\"$order_id\",\"amount\":$amount,\"paymentKey\":\"fake-$order_id\",\"gatewayType\":\"TOSS\"}")
final=""
for _ in $(seq 1 30); do
    final=$(curl -sS "$GW/api/v1/payments/$order_id/status" | jq -r '(.data // .) | .status')
    [ "$final" = "DONE" ] || [ "$final" = "FAILED" ] && break
    sleep 1
done
if wait_stock 1 $(( before - 1 )) 30; then stock_result="decreased-by-1"; else stock_result="not-decreased(now=$(stock_of 1))"; fi
record "payment confirm=$confirm_status final=$final product1-stock=$stock_result"

# ── 3. 같은 확정 메시지 두 번 ────────────────────────────────────────────────
sql "UPDATE stock SET quantity = 10 WHERE product_id = $MSG_PRODUCT"
dup_key="$RUN_TAG-dup"
dup_msg=$(message "$MSG_PRODUCT" 2 "$dup_key" "$RUN_TAG-dup-order")
produce "$MSG_PRODUCT" "$dup_msg"
produce "$MSG_PRODUCT" "$dup_msg"
wait_stock "$MSG_PRODUCT" 8 20 >/dev/null
sleep 5
record "duplicate stock=$(stock_of "$MSG_PRODUCT") dedupe-rows=$(sql "SELECT COUNT(*) FROM stock_commit_dedupe WHERE event_uuid = '$dup_key'")"

# ── 4. 재고 부족 확정 → 격리 ─────────────────────────────────────────────────
sql "UPDATE stock SET quantity = 1 WHERE product_id = $MSG_PRODUCT"
short_order="$RUN_TAG-short-order"
produce "$MSG_PRODUCT" "$(message "$MSG_PRODUCT" 5 "$RUN_TAG-short" "$short_order")"
dlq_key=""
for _ in $(seq 1 6); do
    dlq_key=$(docker exec -i "$KAFKA_CONTAINER" kafka-console-consumer --bootstrap-server localhost:9092 \
        --topic "$DLQ_TOPIC" --from-beginning --timeout-ms 5000 \
        --property print.key=true --property key.separator='|' 2>/dev/null \
        | grep "\"orderId\":\"$short_order\"" | head -1 | cut -d'|' -f1)
    [ -n "$dlq_key" ] && break
done
record "insufficient dlq-key=${dlq_key//$short_order/ORDER} stock=$(stock_of "$MSG_PRODUCT")"

# ── 5. 스모크 ───────────────────────────────────────────────────────────────
bash "$ROOT/scripts/smoke-all.sh" --with-trace > "$OUT/smoke.log" 2>&1
smoke_exit=$?
grep -E "PASS|FAIL" "$OUT/smoke.log" | grep -E "✅|❌" | sed 's/\x1b\[[0-9;]*m//g' > "$OUT/smoke-steps.txt"
record "smoke exit=$smoke_exit steps=$(wc -l < "$OUT/smoke-steps.txt" | tr -d ' ')"
