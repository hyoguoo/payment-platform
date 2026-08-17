-- 상품 단위 재고 atomic 보상 스크립트
-- KEYS[1] = compensation:done:{productId}:orderId  (상품·주문 조합 dedup token)
-- KEYS[2] = stock:{productId}                       (상품 재고 키)
-- ARGV[1] = 복원 수량
-- ARGV[2] = dedup token TTL (초, P8D = 691200)
-- 반환: "ALREADY_DONE" | "OK"

local dedup_key = KEYS[1]
local stock_key = KEYS[2]
local qty = tonumber(ARGV[1])
local ttl = tonumber(ARGV[2])

-- 1. dedup token SETNX — 이미 있으면 ALREADY_DONE
local set_result = redis.call('SETNX', dedup_key, '1')
if set_result == 0 then
    return 'ALREADY_DONE'
end

-- dedup token TTL 설정
redis.call('EXPIRE', dedup_key, ttl)

-- 2. INCRBY (보상은 재고 검증 불필요 — 항상 복원)
redis.call('INCRBY', stock_key, qty)

return 'OK'
