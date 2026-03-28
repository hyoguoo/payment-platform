package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
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

    public static final int EXPIRATION_MINUTES = 30;
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
    private String statusReason;
    private List<PaymentOrder> paymentOrderList;
    private LocalDateTime createdAt;
    private LocalDateTime lastStatusChangedAt;

    public static PaymentEvent create(
            UserInfo userInfo,
            List<ProductInfo> productInfoList,
            String orderId,
            LocalDateTime lastStatusChangedAt
    ) {
        return PaymentEvent.allArgsBuilder()
                .buyerId(userInfo.getId())
                .sellerId(productInfoList.getFirst().getSellerId())
                .orderName(generateOrderName(productInfoList))
                .orderId(orderId)
                .status(PaymentEventStatus.READY)
                .retryCount(0)
                .paymentOrderList(new ArrayList<>())
                .lastStatusChangedAt(lastStatusChangedAt)
                .allArgsBuild();
    }

    private static String generateOrderName(
            List<ProductInfo> productInfoList
    ) {
        return productInfoList.getFirst().getName() + " 포함 " + productInfoList.size() + "건";
    }

    public void execute(String paymentKey, LocalDateTime executedAt, LocalDateTime lastStatusChangedAt) {
        if (this.status != PaymentEventStatus.READY &&
                this.status != PaymentEventStatus.IN_PROGRESS &&
                this.status != PaymentEventStatus.UNKNOWN) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_EXECUTE);
        }
        paymentOrderList.forEach(PaymentOrder::execute);
        this.paymentKey = paymentKey;
        this.status = PaymentEventStatus.IN_PROGRESS;
        this.executedAt = executedAt;
        this.lastStatusChangedAt = lastStatusChangedAt;
    }

    public BigDecimal getTotalAmount() {
        return paymentOrderList.stream()
                .map(PaymentOrder::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void done(LocalDateTime approvedAt, LocalDateTime lastStatusChangedAt) {
        if (this.status != PaymentEventStatus.IN_PROGRESS &&
                this.status != PaymentEventStatus.DONE &&
                this.status != PaymentEventStatus.UNKNOWN) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_SUCCESS);
        }
        this.approvedAt = approvedAt;
        this.status = PaymentEventStatus.DONE;
        this.statusReason = null;
        this.lastStatusChangedAt = lastStatusChangedAt;
        this.paymentOrderList.forEach(PaymentOrder::success);
    }

    public void fail(String failureReason, LocalDateTime lastStatusChangedAt) {
        if (this.status != PaymentEventStatus.READY &&
                this.status != PaymentEventStatus.IN_PROGRESS &&
                this.status != PaymentEventStatus.UNKNOWN) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_FAIL);
        }
        this.status = PaymentEventStatus.FAILED;
        this.statusReason = failureReason;
        this.lastStatusChangedAt = lastStatusChangedAt;
        this.paymentOrderList.forEach(PaymentOrder::fail);
    }

    public void unknown(String reason, LocalDateTime lastStatusChangedAt) {
        if (this.status != PaymentEventStatus.READY &&
                this.status != PaymentEventStatus.IN_PROGRESS &&
                this.status != PaymentEventStatus.UNKNOWN) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_UNKNOWN);
        }
        this.status = PaymentEventStatus.UNKNOWN;
        this.statusReason = reason;
        this.lastStatusChangedAt = lastStatusChangedAt;
        this.paymentOrderList.forEach(PaymentOrder::unknown);
    }

    public void expire(LocalDateTime lastStatusChangedAt) {
        if (this.status != PaymentEventStatus.READY) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_EXPIRE);
        }
        this.status = PaymentEventStatus.EXPIRED;
        this.statusReason = null;
        this.lastStatusChangedAt = lastStatusChangedAt;
        this.paymentOrderList.forEach(PaymentOrder::expire);
    }

    public void addPaymentOrderList(List<PaymentOrder> newPaymentOrderList) {
        this.paymentOrderList.addAll(newPaymentOrderList);
    }

}
