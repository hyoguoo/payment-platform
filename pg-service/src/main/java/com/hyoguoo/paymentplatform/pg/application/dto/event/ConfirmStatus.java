package com.hyoguoo.paymentplatform.pg.application.dto.event;

/**
 * payment.events.confirmed 메시지의 결과 상태 — pg-service 발행 측.
 *
 * <p>와이어 포맷은 {@code ConfirmedEventPayload.status} 에서 String 으로 유지하고,
 * 발행 payload 생성과 내부 분기에서만 이 enum 으로 다룬다.
 */
public enum ConfirmStatus {

    APPROVED,
    FAILED,
    QUARANTINED
}
