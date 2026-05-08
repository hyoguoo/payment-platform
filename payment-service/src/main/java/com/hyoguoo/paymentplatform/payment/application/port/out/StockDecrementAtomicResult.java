package com.hyoguoo.paymentplatform.payment.application.port.out;

/**
 * 결제 단위 atomic 선차감 결과.
 *
 * <ul>
 *   <li>{@link #OK} — 정상 차감 완료</li>
 *   <li>{@link #ALREADY_DONE} — 동일 orderId 의 dedup token 이 이미 존재 (멱등 재진입)</li>
 *   <li>{@link #INSUFFICIENT} — 하나 이상의 상품 재고 부족, 차감 미수행</li>
 * </ul>
 *
 * 인프라 장애 시에는 이 enum 대신 {@link RuntimeException} 이 전파된다.
 */
public enum StockDecrementAtomicResult {

    OK,
    ALREADY_DONE,
    INSUFFICIENT
}
