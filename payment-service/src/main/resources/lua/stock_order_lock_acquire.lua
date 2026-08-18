-- 주문 단위 확정 선점 획득 스크립트.
-- 상품 반복 전체를 감싸는 선점으로, 동시 중복 확정 요청이 하나로 수렴하게 한다.
-- KEYS[1] = order lock key
-- ARGV[1] = 이번 요청의 선점 토큰 (해제 시 비교용)
-- ARGV[2] = 선점 수명 (초) — 명시적 해제를 못한 경우를 위한 회수용 backup
-- 반환: "OK" | "LOCKED"

local lock_key = KEYS[1]
local token = ARGV[1]
local ttl = tonumber(ARGV[2])

local set_result = redis.call('SETNX', lock_key, token)
if set_result == 0 then
    return 'LOCKED'
end

redis.call('EXPIRE', lock_key, ttl)
return 'OK'
