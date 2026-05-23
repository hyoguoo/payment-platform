package com.hyoguoo.paymentplatform.payment.application.dto.event;

/**
 * payment.events.confirmed 메시지의 결과 상태.
 *
 * <p>와이어 포맷은 {@code ConfirmedEventMessage.status} 에서 String 으로 유지하고,
 * 내부 분기에서만 이 enum 으로 변환해 쓴다. 알 수 없는 값은 {@link #UNKNOWN} 으로 흡수해
 * 분기에서 명시적으로 처리한다.
 */
public enum ConfirmStatus {

    APPROVED,
    FAILED,
    QUARANTINED,
    UNKNOWN;

    public static ConfirmStatus from(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        try {
            return ConfirmStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
