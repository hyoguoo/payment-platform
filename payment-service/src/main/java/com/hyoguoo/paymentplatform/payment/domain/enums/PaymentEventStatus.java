package com.hyoguoo.paymentplatform.payment.domain.enums;

public enum PaymentEventStatus {

    READY,
    IN_PROGRESS,
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
            case READY, IN_PROGRESS, QUARANTINED -> false;
        };
    }

    /**
     * EOS 컨슈머 진입 가드 — {@link com.hyoguoo.paymentplatform.payment.application.usecase.PaymentConfirmResultUseCase} 전용.
     *
     * <p>READY, IN_PROGRESS 두 상태에서만 confirm 결과를 적용할 수 있다.
     * QUARANTINED 는 {@link com.hyoguoo.paymentplatform.payment.application.usecase.QuarantineCompensationHandler}
     * 가 전담 처리하므로 여기서는 false.
     * DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED(terminal)도 이미 처리 완료이므로 false.
     *
     * <p>특히 QUARANTINED 를 true 로 바꾸면 늦게 도착한 APPROVED 가 markPaymentAsDone 의
     * not-retryable 예외를 일으켜 DLQ 로 조용히 빠지는 D7 침묵 DLQ 가 재현된다.
     *
     * @return true = 진입 가능 (READY/IN_PROGRESS),
     *         false = 진입 불가 (DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED)
     */
    public boolean canApplyConfirmResult() {
        return switch (this) {
            case READY, IN_PROGRESS -> true;
            case DONE, FAILED, CANCELED, PARTIAL_CANCELED, EXPIRED, QUARANTINED -> false;
        };
    }
}
