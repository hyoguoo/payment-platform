-- 상품 단위 거절 전용 재고 되돌리기 스크립트.
-- 다중 상품 차감 중 재고 부족을 만나 이번 요청이 직접 차감에 성공한 상품만 되돌릴 때 쓴다.
-- 재고를 복원하면서 선차감 표시와 되돌리기 표시를 함께 지워 이번 사이클을 완전히 무효화한다 —
-- 선차감 표시가 남으면 재시도가 이미 처리됨으로 통과해 실제 차감 없이 승인되고,
-- 되돌리기 표시가 남으면 재시도가 만든 새 차감을 다음 사이클의 되돌리기가 못 돌린다.
-- 이 스크립트는 호출자가 이미 자신이 직접 차감한 상품에 한해서만 부르므로 별도 dedup token 이 없다.
-- KEYS[1] = decrement:done:{productId}:orderId
-- KEYS[2] = compensation:done:{productId}:orderId
-- KEYS[3] = stock:{productId}
-- ARGV[1] = 복원 수량
-- 반환: "OK"

local decrement_done_key = KEYS[1]
local compensation_done_key = KEYS[2]
local stock_key = KEYS[3]
local qty = tonumber(ARGV[1])

redis.call('INCRBY', stock_key, qty)
redis.call('DEL', decrement_done_key)
redis.call('DEL', compensation_done_key)

return 'OK'
