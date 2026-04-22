package com.hyoguoo.paymentplatform.product.infrastructure.cache;

import com.hyoguoo.paymentplatform.product.application.port.out.PaymentStockCachePort;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * PaymentStockCachePort Redis 구현체 — 재고 전용 Redis 인스턴스(redis-stock)에 SET.
 * <p>
 * S-3(Redis 직접 쓰기): keyspace {@code stock:{productId}}.
 * <p>
 * 활성화 조건: {@code product.cache.stock-redis.host} 프로퍼티 존재 시.
 * 미설정 시 빈 미등록 → 테스트 환경에서 FakePaymentStockCachePort 주입 가능.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "product.cache.stock-redis.host")
public class StockRedisAdapter implements PaymentStockCachePort {

    private static final String KEY_PREFIX = "stock:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, String> stockRedisTemplate;

    public StockRedisAdapter(
            @Qualifier("stockRedisTemplate") RedisTemplate<String, String> stockRedisTemplate) {
        this.stockRedisTemplate = stockRedisTemplate;
    }

    /**
     * 특정 상품의 재고를 재고 전용 Redis(redis-stock)에 SET한다.
     * keyspace: {@code stock:{productId}}
     * TTL: 24시간 기본값
     *
     * @param productId 상품 식별자
     * @param stock     현재 재고 수량
     */
    @Override
    public void setStock(long productId, int stock) {
        String key = KEY_PREFIX + productId;
        stockRedisTemplate.opsForValue().set(key, String.valueOf(stock), DEFAULT_TTL);
        log.info("StockRedisAdapter: Redis SET key={} stock={}", key, stock);
    }
}
