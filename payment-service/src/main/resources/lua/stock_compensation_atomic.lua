-- 결제 단위 재고 atomic 보상 스크립트
-- KEYS[1]       = compensation:done:{orderId}  (dedup token)
-- KEYS[2..N+1]  = stock:{productId}            (N개 상품 재고 키)
-- ARGV[1..N]    = 복원 수량 N개                (KEYS[2..N+1] 와 1:1 대응)
-- ARGV[N+1]     = dedup token TTL (초, P8D = 691200)
-- 반환: "ALREADY_DONE" | "OK"

local dedup_key = KEYS[1]
local n = #KEYS - 1
local ttl = tonumber(ARGV[n + 1])

-- 1. dedup token SETNX — 이미 있으면 ALREADY_DONE
local set_result = redis.call('SETNX', dedup_key, '1')
if set_result == 0 then
    return 'ALREADY_DONE'
end

-- dedup token TTL 설정
redis.call('EXPIRE', dedup_key, ttl)

-- 2. 전체 INCRBY (보상은 재고 검증 불필요 — 항상 복원)
for i = 1, n do
    redis.call('INCRBY', KEYS[i + 1], ARGV[i])
end

return 'OK'
