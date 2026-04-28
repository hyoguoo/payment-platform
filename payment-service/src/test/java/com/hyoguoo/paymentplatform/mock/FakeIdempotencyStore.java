package com.hyoguoo.paymentplatform.mock;

import com.hyoguoo.paymentplatform.payment.application.dto.IdempotencyResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.IdempotencyStore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class FakeIdempotencyStore implements IdempotencyStore {

    private final ConcurrentHashMap<String, CheckoutResult> store = new ConcurrentHashMap<>();

    @Override
    public IdempotencyResult<CheckoutResult> getOrCreate(String key, Supplier<CheckoutResult> creator) {
        boolean[] created = {false};
        CheckoutResult result = store.computeIfAbsent(key, k -> {
            created[0] = true;
            return creator.get();
        });
        return created[0] ? IdempotencyResult.miss(result) : IdempotencyResult.hit(result);
    }
}
