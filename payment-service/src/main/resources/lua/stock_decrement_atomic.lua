-- 결제 단위 재고 atomic 선차감 스크립트
-- KEYS[1]       = decrement:done:{orderId}  (dedup token)
-- KEYS[2..N+1]  = stock:{productId}         (N개 상품 재고 키)
-- ARGV[1..N]    = 차감 수량 N개             (KEYS[2..N+1] 와 1:1 대응)
-- ARGV[N+1]     = dedup token TTL (초, P8D = 691200)
-- 반환: "ALREADY_DONE" | "INSUFFICIENT" | "OK"

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

-- 2. 모든 상품 재고 GET + 검증 (하나라도 부족하면 INSUFFICIENT)
for i = 1, n do
    local stock = tonumber(redis.call('GET', KEYS[i + 1]) or '0')
    local qty   = tonumber(ARGV[i])
    if stock < qty then
        -- dedup token 삭제 → 재시도 가능
        redis.call('DEL', dedup_key)
        return 'INSUFFICIENT'
    end
end

-- 3. 전체 DECRBY
for i = 1, n do
    redis.call('DECRBY', KEYS[i + 1], ARGV[i])
end

return 'OK'
