package com.hyoguoo.paymentplatform.payment.application.port.out;

/**
 * 주문 단위 발행 행 생성 결과.
 *
 * <ul>
 *   <li>{@link #CREATED} — 신규 삽입 성공(반영 행 1)</li>
 *   <li>{@link #ALREADY_EXISTS} — 반영 행 0, 확인 조회로 기존 행이 잡힘(동시 재진입)</li>
 *   <li>{@link #SAVE_FAILED} — 반영 행 0, 확인 조회에도 행이 없음(진짜 저장 실패)</li>
 * </ul>
 */
public enum PaymentOutboxCreationResult {

    CREATED,
    ALREADY_EXISTS,
    SAVE_FAILED
}
