-- 주문 단위 확정 선점 해제 스크립트.
-- 토큰이 일치할 때만 지운다 — 수명이 지나 다른 요청이 재획득한 뒤에는 토큰이 달라
-- 아무 일도 하지 않는다. 비교 없이 지우면 그 요청의 선점을 잘못 풀어 같은 주문의
-- 상품 반복이 동시에 두 번 돌 수 있다.
-- KEYS[1] = order lock key
-- ARGV[1] = 이번 요청이 선점 때 받은 토큰
-- 반환: 삭제된 키 수(0 또는 1)

local lock_key = KEYS[1]
local token = ARGV[1]

if redis.call('GET', lock_key) == token then
    return redis.call('DEL', lock_key)
else
    return 0
end
