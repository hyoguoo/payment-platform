package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.application.dto.request.CheckoutCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderProduct;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private BigDecimal totalAmount;
    private Boolean isPaymentDone;
    private LocalDateTime approvedAt;

    @Builder(builderMethodName = "requiredBuilder", buildMethodName = "requiredBuild")
    protected PaymentEvent(
            UserInfo userInfo,
            ProductInfo productInfo,
            CheckoutCommand checkoutCommand,
            LocalDateTime now
    ) {
        this.buyerId = userInfo.getId();
        this.sellerId = productInfo.getSellerId();
        this.totalAmount = checkoutCommand.getAmount();

        this.orderName = generateOrderName(productInfo, checkoutCommand.getOrderProduct());
        this.orderId = generateOrderId(now);
        this.isPaymentDone = false;

        validateTotalAmount(
                productInfo,
                checkoutCommand.getOrderProduct()
        );
    }

    private static String generateOrderId(LocalDateTime now) {
        return "ORDER-" + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
    }

    private static String generateOrderName(ProductInfo productInfo, OrderProduct orderProduct) {
        return productInfo.getName() + " " + orderProduct.getQuantity() + "개";
    }

    private void validateTotalAmount(
            ProductInfo productInfo,
            OrderProduct orderProduct
    ) {
        BigDecimal calculatedAmount = productInfo.getPrice()
                .multiply(BigDecimal.valueOf(orderProduct.getQuantity()));

        if (calculatedAmount.compareTo(this.totalAmount) != 0) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_TOTAL_AMOUNT);
        }
    }
}
