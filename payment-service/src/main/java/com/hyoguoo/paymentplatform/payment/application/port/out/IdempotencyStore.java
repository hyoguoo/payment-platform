package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.application.dto.IdempotencyResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import java.util.function.Supplier;

public interface IdempotencyStore {

    IdempotencyResult<CheckoutResult> getOrCreate(String key, Supplier<CheckoutResult> creator);
}
