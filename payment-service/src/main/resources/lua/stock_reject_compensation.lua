-- 상품 단위 거절 전용 재고 되돌리기 스크립트.
-- 다중 상품 차감 중 재고 부족을 만나 이번 요청이 직접 차감에 성공한 상품만 되돌릴 때 쓴다.
-- 되돌리기 표시가 아직 없을 때만 재고를 복원하고, 선차감 표시와 되돌리기 표시는 항상 함께 지워
-- 이번 사이클을 완전히 무효화한다 — 선차감 표시가 남으면 재시도가 이미 처리됨으로 통과해 실제
-- 차감 없이 승인되고, 되돌리기 표시가 남으면 재시도가 만든 새 차감을 다음 사이클의 되돌리기가
-- 못 돌린다.
-- 되돌리기 표시가 이미 있으면(다른 주체가 먼저 조건부 복원을 마쳤거나 이례적으로 재호출된 경우)
-- 복원을 건너뛴다 — 이 스크립트를 부르는 지점은 주문 단위 선점과 상태 배타성으로 실제 동시
-- 경합이 없지만, 그 전제가 코드가 아니라 상황이라 dedup 을 명시적으로 걸어 미래 변경에도
-- 이중 복원이 나지 않게 한다.
-- KEYS[1] = decrement:done:{productId}:orderId
-- KEYS[2] = compensation:done:{productId}:orderId
-- KEYS[3] = stock:{productId}
-- ARGV[1] = 복원 수량
-- 반환: "OK"

local decrement_done_key = KEYS[1]
local compensation_done_key = KEYS[2]
local stock_key = KEYS[3]
local qty = tonumber(ARGV[1])

if redis.call('GET', compensation_done_key) == false then
    redis.call('INCRBY', stock_key, qty)
end
redis.call('DEL', decrement_done_key)
redis.call('DEL', compensation_done_key)

return 'OK'
