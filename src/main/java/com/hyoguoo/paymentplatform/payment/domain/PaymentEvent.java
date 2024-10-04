package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.TossPaymentStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentEvent {

    public static final int RETRYABLE_MINUTES_FOR_IN_PROGRESS = 5;
    public static final int RETRYABLE_LIMIT = 5;

    private Long id;
    private Long buyerId;
    private Long sellerId;
    private String orderName;
    private String orderId;
    private String paymentKey;
    private PaymentEventStatus status;
    private LocalDateTime executedAt;
    private LocalDateTime approvedAt;
    private Integer retryCount;
    private List<PaymentOrder> paymentOrderList;

    @Builder(builderMethodName = "requiredBuilder", buildMethodName = "requiredBuild")
    @SuppressWarnings("unused")
    protected PaymentEvent(
            UserInfo userInfo,
            List<ProductInfo> productInfoList,
            String orderId
    ) {
        this.buyerId = userInfo.getId();
        this.sellerId = productInfoList.getFirst().getSellerId();

        this.orderName = generateOrderName(productInfoList);
        this.orderId = orderId;
        this.status = PaymentEventStatus.READY;
        this.retryCount = 0;
        this.paymentOrderList = new ArrayList<>();
    }

    private static String generateOrderName(
            List<ProductInfo> productInfoList
    ) {
        return productInfoList.getFirst().getName() + " 포함 " + productInfoList.size() + "건";
    }

    public void execute(String paymentKey, LocalDateTime executedAt) {
        if (this.status != PaymentEventStatus.READY &&
                this.status != PaymentEventStatus.IN_PROGRESS &&
                this.status != PaymentEventStatus.UNKNOWN) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_EXECUTE);
        }
        paymentOrderList.forEach(PaymentOrder::execute);
        this.paymentKey = paymentKey;
        this.status = PaymentEventStatus.IN_PROGRESS;
        this.executedAt = executedAt;
    }

    public void validateCompletionStatus(PaymentConfirmCommand paymentConfirmCommand, TossPaymentInfo paymentInfo) {
        if (!this.buyerId.equals(paymentConfirmCommand.getUserId())) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_USER_ID);
        }

        if (!paymentConfirmCommand.getPaymentKey().equals(paymentInfo.getPaymentKey()) ||
                !paymentConfirmCommand.getPaymentKey().equals(this.paymentKey)) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_PAYMENT_KEY);
        }

        if (paymentConfirmCommand.getAmount().compareTo(this.getTotalAmount()) != 0) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_TOTAL_AMOUNT);
        }

        if (!this.orderId.equals(paymentConfirmCommand.getOrderId())) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_ORDER_ID);
        }

        if (paymentInfo.getPaymentDetails().getStatus() != TossPaymentStatus.IN_PROGRESS &&
                paymentInfo.getPaymentDetails().getStatus() != TossPaymentStatus.DONE) {
            throw PaymentStatusException.of(PaymentErrorCode.NOT_IN_PROGRESS_ORDER);
        }
    }

    public BigDecimal getTotalAmount() {
        return paymentOrderList.stream()
                .map(PaymentOrder::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void done(LocalDateTime approvedAt) {
        if (this.status != PaymentEventStatus.IN_PROGRESS &&
                this.status != PaymentEventStatus.DONE &&
                this.status != PaymentEventStatus.UNKNOWN) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_SUCCESS);
        }
        this.approvedAt = approvedAt;
        this.status = PaymentEventStatus.DONE;
        this.paymentOrderList.forEach(PaymentOrder::success);
    }

    public void fail() {
        if (this.status != PaymentEventStatus.IN_PROGRESS &&
                this.status != PaymentEventStatus.UNKNOWN) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_FAIL);
        }
        this.status = PaymentEventStatus.FAILED;
        this.paymentOrderList.forEach(PaymentOrder::fail);
    }

    public void unknown() {
        if (this.status != PaymentEventStatus.READY &&
                this.status != PaymentEventStatus.IN_PROGRESS) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_SUCCESS);
        }
        this.status = PaymentEventStatus.UNKNOWN;
        this.paymentOrderList.forEach(PaymentOrder::unknown);
    }

    public void addPaymentOrderList(List<PaymentOrder> newPaymentOrderList) {
        this.paymentOrderList.addAll(newPaymentOrderList);
    }

    public void increaseRetryCount() {
        if (this.status != PaymentEventStatus.UNKNOWN) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_RETRY);
        }
        retryCount++;
    }

    public boolean isRetryable(LocalDateTime now) {
        return (isRetryableInProgress(now) || this.status == PaymentEventStatus.UNKNOWN) &&
                canAttemptRetryCount();
    }

    private boolean isRetryableInProgress(LocalDateTime now) {
        return this.executedAt.plusMinutes(RETRYABLE_MINUTES_FOR_IN_PROGRESS).isBefore(now)
                && this.status == PaymentEventStatus.IN_PROGRESS;
    }

    private boolean canAttemptRetryCount() {
        return this.retryCount < RETRYABLE_LIMIT;
    }
}
