package com.hyoguoo.paymentplatform.payment.application.port.out;

/**
 * 격리 복구 전용 조건부 보상 결과.
 *
 * <ul>
 *   <li>{@link #OK} — {@code decrement:done} 토큰 존재 확인 후 정상 보상 완료</li>
 *   <li>{@link #ALREADY_DONE} — 동일 orderId 의 {@code compensation:done} 토큰이 이미 존재 (멱등 재진입)</li>
 *   <li>{@link #NO_DECREMENT} — {@code decrement:done} 토큰이 없어 보상을 수행하지 않음(유령 재고 방지)</li>
 * </ul>
 *
 * 인프라 장애 시에는 이 enum 대신 {@link RuntimeException} 이 전파된다.
 */
public enum StockRecoveryCompensationResult {

    OK,
    ALREADY_DONE,
    NO_DECREMENT
}
