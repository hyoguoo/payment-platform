package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentProcessStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentProcess {

    private Long id;
    private String orderId;
    private PaymentProcessStatus status;
    private LocalDateTime completedAt;
    private LocalDateTime failedAt;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static PaymentProcess createProcessing(String orderId) {
        return PaymentProcess.allArgsBuilder()
                .orderId(orderId)
                .status(PaymentProcessStatus.PROCESSING)
                .allArgsBuild();
    }

    public void complete(LocalDateTime completedAt) {
        if (this.status == PaymentProcessStatus.COMPLETED) {
            return;
        }

        if (this.status == PaymentProcessStatus.FAILED) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_COMPLETE);
        }

        this.status = PaymentProcessStatus.COMPLETED;
        this.completedAt = completedAt;
        this.failureReason = null;
    }

    public void fail(LocalDateTime failedAt, String failureReason) {
        if (this.status == PaymentProcessStatus.FAILED) {
            return;
        }

        if (this.status == PaymentProcessStatus.COMPLETED) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_FAIL);
        }

        this.status = PaymentProcessStatus.FAILED;
        this.failedAt = failedAt;
        this.failureReason = failureReason;
    }

    public boolean isProcessing() {
        return this.status == PaymentProcessStatus.PROCESSING;
    }

    public boolean isFinished() {
        return this.status == PaymentProcessStatus.COMPLETED || this.status == PaymentProcessStatus.FAILED;
    }
}
