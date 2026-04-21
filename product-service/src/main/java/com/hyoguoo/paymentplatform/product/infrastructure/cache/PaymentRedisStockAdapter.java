package com.hyoguoo.paymentplatform.product.infrastructure.cache;

import com.hyoguoo.paymentplatform.product.application.port.out.PaymentStockCachePort;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * PaymentStockCachePort Redis 구현체 — payment-service 전용 Redis 인스턴스(redis-payment)에 SET.
 * <p>
 * S-3(Redis 직접 쓰기): keyspace {@code stock:{productId}}.
 * <p>
 * 활성화 조건: {@code product.cache.payment-redis.host} 프로퍼티 존재 시.
 * 미설정 시 빈 미등록 → 테스트 환경에서 FakePaymentStockCachePort 주입 가능.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "product.cache.payment-redis.host")
public class PaymentRedisStockAdapter implements PaymentStockCachePort {

    private static final String KEY_PREFIX = "stock:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, String> paymentRedisTemplate;

    public PaymentRedisStockAdapter(
            @Qualifier("paymentRedisTemplate") RedisTemplate<String, String> paymentRedisTemplate) {
        this.paymentRedisTemplate = paymentRedisTemplate;
    }

    /**
     * 특정 상품의 재고를 payment-side Redis에 SET한다.
     * keyspace: {@code stock:{productId}}
     * TTL: 24시간 기본값
     *
     * @param productId 상품 식별자
     * @param stock     현재 재고 수량
     */
    @Override
    public void setStock(long productId, int stock) {
        String key = KEY_PREFIX + productId;
        paymentRedisTemplate.opsForValue().set(key, String.valueOf(stock), DEFAULT_TTL);
        log.info("PaymentRedisStockAdapter: Redis SET key={} stock={}", key, stock);
    }
}
