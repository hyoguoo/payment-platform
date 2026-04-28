package com.hyoguoo.paymentplatform.payment.infrastructure.cache;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 재고 캐시 Redis 어댑터 — payment-service 의 선차감 캐시.
 *
 * <p>keyspace: {@code stock:{productId}}.
 * Lua 스크립트로 DECRBY → 음수 감지 → INCRBY 복구를 원자적으로 수행한다.
 * AOF(appendonly yes) 전제 하에 재시작 복원이 보장된다.
 * Redis 연결 실패 시 예외를 그대로 전파한다 — QUARANTINED 분기 결정은 상위 계층 책임.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockCacheRedisAdapter implements StockCachePort {

    private static final String KEY_PREFIX = "stock:";

    private static final DefaultRedisScript<Long> DECREMENT_SCRIPT;

    static {
        DECREMENT_SCRIPT = new DefaultRedisScript<>();
        DECREMENT_SCRIPT.setLocation(new ClassPathResource("lua/stock_decrement.lua"));
        DECREMENT_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stockCacheRedisTemplate;

    @Override
    public boolean decrement(Long productId, int quantity) {
        Long result = stockCacheRedisTemplate.execute(
                DECREMENT_SCRIPT,
                List.of(KEY_PREFIX + productId),
                String.valueOf(quantity)
        );
        return result != null && result == 1L;
    }

    @Override
    public void rollback(Long productId, int quantity) {
        stockCacheRedisTemplate.opsForValue().increment(KEY_PREFIX + productId, quantity);
    }

    @Override
    public void increment(Long productId, int quantity) {
        stockCacheRedisTemplate.opsForValue().increment(KEY_PREFIX + productId, quantity);
    }

    @Override
    public int current(Long productId) {
        String value = stockCacheRedisTemplate.opsForValue().get(KEY_PREFIX + productId);
        return value == null ? 0 : Integer.parseInt(value);
    }

    @Override
    public Optional<Integer> findCurrent(Long productId) {
        String value = stockCacheRedisTemplate.opsForValue().get(KEY_PREFIX + productId);
        return value == null ? Optional.empty() : Optional.of(Integer.parseInt(value));
    }

    @Override
    public void set(Long productId, int quantity) {
        stockCacheRedisTemplate.opsForValue().set(KEY_PREFIX + productId, String.valueOf(quantity));
    }
}
