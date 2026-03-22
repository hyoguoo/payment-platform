package com.hyoguoo.paymentplatform.mock;

import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.application.port.IdempotencyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class FakeIdempotencyStore implements IdempotencyStore {

    private final Map<String, CheckoutResult> store = new HashMap<>();

    @Override
    public Optional<CheckoutResult> getIfPresent(String key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void put(String key, CheckoutResult result) {
        store.put(key, result);
    }
}
