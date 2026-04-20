package com.hyoguoo.paymentplatform.payment.infrastructure.idempotency;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hyoguoo.paymentplatform.payment.application.dto.IdempotencyResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.application.port.IdempotencyStore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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
    public IdempotencyResult<CheckoutResult> getOrCreate(String key, Supplier<CheckoutResult> creator) {
        boolean[] loaderCalled = {false};
        CheckoutResult result = cache.get(key, k -> {
            loaderCalled[0] = true;
            return creator.get();
        });
        return loaderCalled[0] ? IdempotencyResult.miss(result) : IdempotencyResult.hit(result);
    }
}
