package com.hyoguoo.paymentplatform.payment.application.dto;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class IdempotencyResult<T> {

    private final T value;
    private final boolean duplicate;

    public static <T> IdempotencyResult<T> hit(T value) {
        return new IdempotencyResult<>(value, true);
    }

    public static <T> IdempotencyResult<T> miss(T value) {
        return new IdempotencyResult<>(value, false);
    }
}
