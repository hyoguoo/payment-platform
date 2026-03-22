package com.hyoguoo.paymentplatform.payment.infrastructure.idempotency;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.application.port.IdempotencyStore;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyStoreImpl implements IdempotencyStore {

    private final Cache<String, CheckoutResult> cache;

    public IdempotencyStoreImpl() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.SECONDS)
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
