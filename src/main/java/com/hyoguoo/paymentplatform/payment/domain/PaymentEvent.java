package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.TossPaymentStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
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

    private Long id;
    private Long buyerId;
    private Long sellerId;
    private String orderName;
    private String orderId;
    private String paymentKey;
    private PaymentEventStatus status;
    private LocalDateTime approvedAt;
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
        this.paymentOrderList = new ArrayList<>();
    }

    private static String generateOrderName(
            List<ProductInfo> productInfoList
    ) {
        return productInfoList.getFirst().getName() + " 포함 " + productInfoList.size() + "건";
    }

    public void execute(String paymentKey) {
        if (this.status != PaymentEventStatus.READY) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_STATUS_TO_EXECUTE);
        }
        paymentOrderList.forEach(PaymentOrder::execute);
        this.paymentKey = paymentKey;
        this.status = PaymentEventStatus.IN_PROGRESS;
    }

    public void validate(PaymentConfirmCommand paymentConfirmCommand, TossPaymentInfo paymentInfo) {
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

        if (paymentInfo.getPaymentDetails().getStatus() != TossPaymentStatus.IN_PROGRESS) {
            throw PaymentValidException.of(PaymentErrorCode.NOT_IN_PROGRESS_ORDER);
        }

        if (!this.orderId.equals(paymentInfo.getOrderId()) ||
                !this.orderId.equals(paymentConfirmCommand.getOrderId())) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_ORDER_ID);
        }
    }

    public BigDecimal getTotalAmount() {
        return paymentOrderList.stream()
                .map(PaymentOrder::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void done(LocalDateTime approvedAt) {
        this.approvedAt = approvedAt;
        this.status = PaymentEventStatus.DONE;
        this.paymentOrderList.forEach(PaymentOrder::paymentDone);
    }

    public void fail() {
        this.status = PaymentEventStatus.FAILED;
        this.paymentOrderList.forEach(PaymentOrder::fail);
    }

    public void unknown() {
        this.status = PaymentEventStatus.UNKNOWN;
        this.paymentOrderList.forEach(PaymentOrder::unknown);
    }
}
