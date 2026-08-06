package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.domain.enums.BackoffType;
import java.time.Duration;

public record RetryPolicy(
        BackoffType backoffType,
        long baseDelayMs,
        long maxDelayMs
) {

    // 소진 종결이 없어 retryCount 가 무한히 커질 수 있다. baseDelayMs * (1L << retryCount) 는
    // retryCount 가 대략 54를 넘으면 long 곱셈이 넘쳐 음수가 되고, 64 이상이면 시프트 자체가
    // 순환(자바의 long 시프트는 거리 % 64)해 값이 뒤죽박죽된다. 시프트 전에 회차를 상한으로
    // 잘라 두 문제를 모두 막는다 — maxDelayMs 도달에 필요한 회차보다 항상 넉넉히 크다.
    private static final int MAX_EXPONENTIAL_SHIFT = 40;

    public Duration nextDelay(int retryCount) {
        return switch (backoffType) {
            case FIXED -> Duration.ofMillis(baseDelayMs);
            case EXPONENTIAL -> {
                int cappedShift = Math.min(retryCount, MAX_EXPONENTIAL_SHIFT);
                yield Duration.ofMillis(Math.min(baseDelayMs * (1L << cappedShift), maxDelayMs));
            }
        };
    }
}
