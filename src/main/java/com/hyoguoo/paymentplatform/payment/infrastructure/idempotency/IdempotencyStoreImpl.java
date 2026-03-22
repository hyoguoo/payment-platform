package com.hyoguoo.paymentplatform.payment.infrastructure.idempotency;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.application.port.IdempotencyStore;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class IdempotencyStoreImpl implements IdempotencyStore {

    private final IdempotencyProperties idempotencyProperties;
    private Cache<String, CheckoutResult> cache;

    @jakarta.annotation.PostConstruct
    void init() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(idempotencyProperties.getMaximumSize())
                .expireAfterWrite(idempotencyProperties.getExpireAfterWriteSeconds(), TimeUnit.SECONDS)
                .build();
    }

    @Override
    public Optional<CheckoutResult> getIfPresent(String key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    @Override
    public void put(String key, CheckoutResult result) {
        cache.put(key, result);
    }
}
