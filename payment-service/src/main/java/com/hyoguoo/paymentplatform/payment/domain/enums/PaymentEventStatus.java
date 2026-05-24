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
     * 보상 핸들러 진입 가능 여부를 판정한다.
     *
     * <p>READY, IN_PROGRESS, RETRYING 세 상태에서만 재고 차감이 발생했을 수 있다.
     * QUARANTINED 는 {@link com.hyoguoo.paymentplatform.payment.application.usecase.QuarantineCompensationHandler}
     * 가 전담 처리하므로 여기서는 false.
     * DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED(terminal)도 이미 처리 완료이므로 false.
     *
     * <p>두 곳에서 진입 가드로 쓰인다 — {@link com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator#executePaymentFailureCompensationWithOutbox}
     * 의 보상 경로와 {@link com.hyoguoo.paymentplatform.payment.application.usecase.PaymentConfirmResultUseCase} 의 EOS 컨슈머.
     *
     * <p>판정 매트릭스를 바꾸면 두 가드가 함께 영향을 받는다. 특히 QUARANTINED 를 진입 가능(true)으로 바꾸면
     * 늦게 도착한 APPROVED 가 markPaymentAsDone 의 not-retryable 예외를 일으켜 DLQ 로 조용히 빠질 수 있다.
     *
     * @return true = 진입 가능 (READY/IN_PROGRESS/RETRYING),
     *         false = 진입 불가 (DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED)
     */
    public boolean isCompensatableByFailureHandler() {
        return switch (this) {
            case READY, IN_PROGRESS, RETRYING -> true;
            case DONE, FAILED, CANCELED, PARTIAL_CANCELED, EXPIRED, QUARANTINED -> false;
        };
    }
}
