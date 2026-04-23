package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
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

    public static final int EXPIRATION_MINUTES = 30;

    private Long id;
    private Long buyerId;
    private Long sellerId;
    private String orderName;
    private String orderId;
    private String paymentKey;
    private PaymentGatewayType gatewayType;
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
            LocalDateTime lastStatusChangedAt,
            PaymentGatewayType gatewayType
    ) {
        return PaymentEvent.allArgsBuilder()
                .buyerId(userInfo.getId())
                .sellerId(productInfoList.getFirst().getSellerId())
                .orderName(generateOrderName(productInfoList))
                .orderId(orderId)
                .gatewayType(gatewayType)
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
                this.status != PaymentEventStatus.IN_PROGRESS) {
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

    public void toRetrying(LocalDateTime lastStatusChangedAt) {
        if (this.status != PaymentEventStatus.READY &&
                this.status != PaymentEventStatus.IN_PROGRESS &&
                this.status != PaymentEventStatus.RETRYING) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_RETRY);
        }
        this.retryCount++;
        this.status = PaymentEventStatus.RETRYING;
        this.lastStatusChangedAt = lastStatusChangedAt;
    }

    public void done(LocalDateTime approvedAt, LocalDateTime lastStatusChangedAt) {
        if (approvedAt == null) {
            throw PaymentStatusException.of(PaymentErrorCode.MISSING_APPROVED_AT);
        }
        if (this.status != PaymentEventStatus.IN_PROGRESS &&
                this.status != PaymentEventStatus.RETRYING &&
                this.status != PaymentEventStatus.DONE) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_SUCCESS);
        }
        this.approvedAt = approvedAt;
        this.status = PaymentEventStatus.DONE;
        this.statusReason = null;
        this.lastStatusChangedAt = lastStatusChangedAt;
        this.paymentOrderList.forEach(PaymentOrder::success);
    }

    public void fail(String failureReason, LocalDateTime lastStatusChangedAt) {
        if (isTerminalStatus()) {
            return;
        }
        if (this.status != PaymentEventStatus.READY &&
                this.status != PaymentEventStatus.IN_PROGRESS &&
                this.status != PaymentEventStatus.RETRYING) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_FAIL);
        }
        this.status = PaymentEventStatus.FAILED;
        this.statusReason = failureReason;
        this.lastStatusChangedAt = lastStatusChangedAt;
        this.paymentOrderList.forEach(PaymentOrder::fail);
    }

    private boolean isTerminalStatus() {
        return this.status.isTerminal();
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

    public void validateConfirmRequest(Long userId, BigDecimal amount, String orderId, String paymentKey) {
        if (!this.buyerId.equals(userId)) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_USER_ID);
        }
        if (amount.compareTo(getTotalAmount()) != 0) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_TOTAL_AMOUNT);
        }
        if (!this.orderId.equals(orderId)) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_ORDER_ID);
        }
        if (this.paymentKey != null && !this.paymentKey.equals(paymentKey)) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_PAYMENT_KEY);
        }
    }

    public void quarantine(String reason, LocalDateTime lastStatusChangedAt) {
        if (this.status.isTerminal()) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_QUARANTINE);
        }
        this.status = PaymentEventStatus.QUARANTINED;
        this.statusReason = reason;
        this.lastStatusChangedAt = lastStatusChangedAt;
    }

    /**
     * Reconciler가 timeout된 IN_FLIGHT(IN_PROGRESS) 레코드를 READY 상태로 복원.
     * 재시도 스케줄러가 재처리할 수 있도록 대기열로 되돌린다.
     * IN_PROGRESS 상태에서만 호출 가능 (다른 상태는 그대로 유지).
     *
     * @param lastStatusChangedAt 상태 변경 시각
     */
    public void resetToReady(LocalDateTime lastStatusChangedAt) {
        if (this.status != PaymentEventStatus.IN_PROGRESS) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_RESET);
        }
        this.status = PaymentEventStatus.READY;
        this.lastStatusChangedAt = lastStatusChangedAt;
    }

    public void addPaymentOrderList(List<PaymentOrder> newPaymentOrderList) {
        this.paymentOrderList.addAll(newPaymentOrderList);
    }

}
