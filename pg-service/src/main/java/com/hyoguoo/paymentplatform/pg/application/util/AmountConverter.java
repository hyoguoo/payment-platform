package com.hyoguoo.paymentplatform.pg.application.util;

import java.math.BigDecimal;

/**
 * BigDecimal ↔ long 금액 변환 유틸리티.
 * discuss-domain-5 minor: 원화 최소 단위는 정수 — fractional digit 거부.
 *
 * <p>pg_inbox.amount 컬럼 저장 시 반드시 이 변환을 경유한다 — 소수점·음수 진입 차단.
 *
 * <p>application 계층이 infrastructure 패키지를 직접 참조하지 않도록 hexagonal layer 규약을 따른다.
 * 모듈 간 공유 jar 금지 — pg-service 내부 유틸리티로만 보유한다.
 */
public final class AmountConverter {

    private AmountConverter() {
    }

    /**
     * BigDecimal → long 엄격 변환.
     *
     * <ul>
     *   <li>null → {@link IllegalArgumentException}</li>
     *   <li>음수 → {@link ArithmeticException} ("amount must be non-negative")</li>
     *   <li>소수 자리가 있는 경우 → {@link ArithmeticException} ("amount must be integral")</li>
     *   <li>정수 값이면 trailing zeros ({@code 1000.00}) 도 허용 — Kafka JSON 역직렬화 호환.</li>
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
        if (amount.signum() < 0) {
            throw new ArithmeticException("amount must be non-negative, was: " + amount.toPlainString());
        }
        try {
            return amount.longValueExact();
        } catch (ArithmeticException e) {
            throw new ArithmeticException(
                    "amount must be integral (no fractional part), was: " + amount.toPlainString());
        }
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
