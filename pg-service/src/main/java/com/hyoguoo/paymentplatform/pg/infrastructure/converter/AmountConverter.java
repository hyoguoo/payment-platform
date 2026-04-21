package com.hyoguoo.paymentplatform.pg.infrastructure.converter;

import java.math.BigDecimal;

/**
 * BigDecimal ↔ long 금액 변환 유틸리티.
 * discuss-domain-5 minor: 원화 최소 단위는 정수 — scale=0 강제 검증.
 *
 * <p>ADR-21 보강(T2b-04): pg_inbox.amount 컬럼 저장 시 이 변환을 통해야 한다.
 */
public final class AmountConverter {

    private AmountConverter() {
    }

    /**
     * BigDecimal → long 엄격 변환.
     *
     * <ul>
     *   <li>null → {@link IllegalArgumentException}</li>
     *   <li>scale &gt; 0 → {@link ArithmeticException} ("amount scale must be 0, was: {scale}")</li>
     *   <li>음수 → {@link ArithmeticException} ("amount must be non-negative")</li>
     *   <li>정상 → long 반환</li>
     * </ul>
     *
     * @param amount 변환할 금액
     * @return 정수 변환 결과
     */
    public static long fromBigDecimalStrict(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
        if (amount.scale() > 0) {
            throw new ArithmeticException("amount scale must be 0, was: " + amount.scale());
        }
        if (amount.signum() < 0) {
            throw new ArithmeticException("amount must be non-negative, was: " + amount.toPlainString());
        }
        return amount.longValueExact();
    }

    /**
     * long → BigDecimal 역변환 보조 (scale=0).
     *
     * @param amount 원화 정수 금액
     * @return {@link BigDecimal} (scale=0)
     */
    public static BigDecimal toBigDecimal(long amount) {
        return BigDecimal.valueOf(amount);
    }
}
