-- 상품 단위 격리 복구 전용 조건부 재고 보상 스크립트
-- decrement:done 토큰이 존재할 때만 보상을 수행한다 (유령 재고 방지).
-- KEYS[1] = decrement:done:{productId}:orderId     (선차감 여부 판정 토큰)
-- KEYS[2] = compensation:done:{productId}:orderId  (dedup token)
-- KEYS[3] = stock:{productId}                       (상품 재고 키)
-- ARGV[1] = 복원 수량
-- ARGV[2] = dedup token TTL (초, P8D = 691200)
-- 반환: "NO_DECREMENT" | "ALREADY_DONE" | "OK"

local decrement_done_key = KEYS[1]
local compensation_done_key = KEYS[2]
local stock_key = KEYS[3]
local qty = tonumber(ARGV[1])
local ttl = tonumber(ARGV[2])

-- 1. decrement:done 존재 여부 우선 확인 — 없으면 보상 대상이 아니므로 즉시 종료
if redis.call('EXISTS', decrement_done_key) == 0 then
    return 'NO_DECREMENT'
end

-- 2. compensation:done dedup token SETNX — 이미 있으면 ALREADY_DONE
local set_result = redis.call('SETNX', compensation_done_key, '1')
if set_result == 0 then
    return 'ALREADY_DONE'
end

-- dedup token TTL 설정
redis.call('EXPIRE', compensation_done_key, ttl)

-- 3. INCRBY (보상은 재고 검증 불필요 — 항상 복원)
redis.call('INCRBY', stock_key, qty)

return 'OK'
