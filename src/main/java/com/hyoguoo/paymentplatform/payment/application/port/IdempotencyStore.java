package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import java.util.Optional;

public interface IdempotencyStore {

    Optional<CheckoutResult> getIfPresent(String key);

    void put(String key, CheckoutResult result);
}
