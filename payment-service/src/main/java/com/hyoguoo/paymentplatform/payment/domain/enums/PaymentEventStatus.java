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
     * 보상 핸들러 진입 가능 여부를 판정한다 (D7 가드 SSOT).
     *
     * <p>READY, IN_PROGRESS, RETRYING 세 상태에서만 재고 차감이 발생했을 수 있다.
     * QUARANTINED 는 {@link com.hyoguoo.paymentplatform.payment.application.usecase.QuarantineCompensationHandler}
     * 가 전담 처리하므로 여기서는 false.
     * DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED(terminal)도 이미 처리 완료이므로 false.
     *
     * <p><b>두 사용처</b>:
     * <ol>
     *   <li>{@link com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator#executePaymentFailureCompensationWithOutbox}
     *       — 보상 경로(SCR) 진입 가드</li>
     *   <li>{@link com.hyoguoo.paymentplatform.payment.application.usecase.PaymentConfirmResultUseCase}
     *       — EOS 컨슈머 진입 가드 (PAYMENT-EOS-TRANSITION D7, PET-8에서 추가 예정)</li>
     * </ol>
     *
     * <p><b>변경 시 영향 경고</b>: 이 메서드의 판정 매트릭스를 변경하면
     * D7 EOS 컨슈머 가드와 보상 핸들러 진입 가드 둘 다 영향. 특히 QUARANTINED 가
     * 진입 가능(true)으로 변경되면 늦은 APPROVED 도착 시 markPaymentAsDone 의
     * not-retryable PaymentStatusException → DLQ silent 분기 위험(DR-3).
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
