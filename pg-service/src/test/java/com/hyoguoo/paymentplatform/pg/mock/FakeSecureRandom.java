package com.hyoguoo.paymentplatform.pg.mock;

import java.security.SecureRandom;

/**
 * SecureRandom Fake — nextDouble() 이 항상 고정값을 반환해 재시도 백오프 지터 계산을 결정적으로 만든다.
 *
 * <p>{@link com.hyoguoo.paymentplatform.pg.domain.RetryPolicy#computeBackoff} 의 equal jitter 계산은
 * {@code nextDouble() * 0.5 - 0.25} 형태라, 0.5 를 반환하면 jitter factor 가 0 이 되어 backoff 가
 * base 값 그대로 나온다.
 */
public class FakeSecureRandom extends SecureRandom {

    private final double fixedValue;

    public FakeSecureRandom(double fixedValue) {
        this.fixedValue = fixedValue;
    }

    @Override
    public double nextDouble() {
        return fixedValue;
    }
}
