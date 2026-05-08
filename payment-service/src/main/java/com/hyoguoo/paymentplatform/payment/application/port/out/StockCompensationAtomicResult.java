package com.hyoguoo.paymentplatform.payment.application.port.out;

/**
 * 결제 단위 atomic 보상(복원) 결과.
 *
 * <ul>
 *   <li>{@link #OK} — 정상 보상 완료</li>
 *   <li>{@link #ALREADY_DONE} — 동일 orderId 의 dedup token 이 이미 존재 (멱등 재진입)</li>
 * </ul>
 *
 * 인프라 장애 시에는 이 enum 대신 {@link RuntimeException} 이 전파된다.
 */
public enum StockCompensationAtomicResult {

    OK,
    ALREADY_DONE
}
