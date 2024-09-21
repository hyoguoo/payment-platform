package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
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
    private Boolean isPaymentDone;
    private LocalDateTime approvedAt;
    private List<PaymentOrder> paymentOrderList = new ArrayList<>();

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
        this.isPaymentDone = false;
    }

    private static String generateOrderName(
            List<ProductInfo> productInfoList
    ) {
        return productInfoList.getFirst().getName() + " 포함 " + productInfoList.size() + "건";
    }

    public void execute(String paymentKey) {
        if (Boolean.TRUE.equals(this.isPaymentDone)) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_STATUS_TO_EXECUTE);
        }
        paymentOrderList.forEach(PaymentOrder::execute);
        this.paymentKey = paymentKey;
    }

    public void validate(PaymentConfirmCommand paymentConfirmCommand, TossPaymentInfo paymentInfo) {
        if (!this.buyerId.equals(paymentConfirmCommand.getUserId())) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_USER_ID);
        }

        if (!paymentConfirmCommand.getPaymentKey().equals(paymentInfo.getPaymentKey())) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_PAYMENT_KEY);
        }

        if (paymentConfirmCommand.getAmount().compareTo(this.getTotalAmount()) != 0) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_TOTAL_AMOUNT);
        }
    }

    public void fail() {
        this.isPaymentDone = false;
        this.paymentOrderList.forEach(PaymentOrder::fail);
    }

    public void paymentDone(LocalDateTime approvedAt) {
        this.approvedAt = approvedAt;
        this.isPaymentDone = true;
        this.paymentOrderList.forEach(PaymentOrder::paymentDone);
    }

    public BigDecimal getTotalAmount() {
        return paymentOrderList.stream()
                .map(PaymentOrder::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void unknown() {
        this.isPaymentDone = false;
        this.paymentOrderList.forEach(PaymentOrder::unknown);
    }
}
