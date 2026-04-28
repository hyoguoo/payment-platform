package com.hyoguoo.paymentplatform.payment.domain.enums;

public enum PaymentEventStatus {

    READY,
    IN_PROGRESS,
    RETRYING,
    DONE,
    FAILED,
    CANCELED,
    PARTIAL_CANCELED,
    EXPIRED,
    QUARANTINED;

    /**
     * 종결 상태 여부를 반환한다.
     * DONE, FAILED, CANCELED, PARTIAL_CANCELED, EXPIRED가 terminal.
     * QUARANTINED는 후속 복구 워커가 보정/포기 결정하는 대기 상태이므로 non-terminal.
     * 이 메서드는 LOCAL_TERMINAL_STATUSES Set 중복 선언을 대체하는 SSOT 판별자다.
     */
    public boolean isTerminal() {
        return switch (this) {
            case DONE, FAILED, CANCELED, PARTIAL_CANCELED, EXPIRED -> true;
            case READY, IN_PROGRESS, RETRYING, QUARANTINED -> false;
        };
    }

    /**
     * 일반 보상 경로(executePaymentFailureCompensationWithOutbox)에서 재고 복원이 허용되는 상태인지 반환한다.
     * READY, IN_PROGRESS, RETRYING만 보상 대상이며, 이 세 상태에서만 재고 차감이 발생했을 수 있다.
     * QUARANTINED 는 QuarantineCompensationHandler 가 전담 처리하므로 여기서는 false.
     * DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED(terminal)도 이미 처리 완료이므로 false.
     */
    public boolean isCompensatableByFailureHandler() {
        return switch (this) {
            case READY, IN_PROGRESS, RETRYING -> true;
            case DONE, FAILED, CANCELED, PARTIAL_CANCELED, EXPIRED, QUARANTINED -> false;
        };
    }
}
