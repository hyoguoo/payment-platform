package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "build")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentOutbox {

    public static final int RETRYABLE_LIMIT = 5;

    private Long id;
    private String orderId;
    private PaymentOutboxStatus status;
    private int retryCount;
    private LocalDateTime inFlightAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static PaymentOutbox createPending(String orderId) {
        return PaymentOutbox.allArgsBuilder()
                .orderId(orderId)
                .status(PaymentOutboxStatus.PENDING)
                .retryCount(0)
                .build();
    }

    public void toInFlight(LocalDateTime inFlightAt) {
        if (this.status != PaymentOutboxStatus.PENDING) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_IN_FLIGHT);
        }
        this.status = PaymentOutboxStatus.IN_FLIGHT;
        this.inFlightAt = inFlightAt;
    }

    public void toDone() {
        this.status = PaymentOutboxStatus.DONE;
    }

    public void toFailed() {
        this.status = PaymentOutboxStatus.FAILED;
    }

    public boolean isRetryable() {
        return this.retryCount < RETRYABLE_LIMIT;
    }

    public void incrementRetryCount() {
        this.retryCount++;
        this.status = PaymentOutboxStatus.PENDING;
    }
}
