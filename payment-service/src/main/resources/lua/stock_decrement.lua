-- 재고 캐시 원자 차감 스크립트 (DECRBY → 음수 감지 → INCRBY 복구)
-- KEYS[1] = stock:{productId}
-- ARGV[1] = qty (양수)
-- 반환:  1 = 차감 성공,  -1 = 재고 부족(INCRBY로 복구 완료)
local after = redis.call('DECRBY', KEYS[1], ARGV[1])
if after < 0 then
  redis.call('INCRBY', KEYS[1], ARGV[1])
  return -1
end
return 1
