package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.application.dto.request.CheckoutCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderProduct;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
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
    }

    private static String generateOrderId(LocalDateTime now) {
        return "ORDER-" + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
    }

    private static String generateOrderName(ProductInfo productInfo, OrderProduct orderProduct) {
        return productInfo.getName() + " " + orderProduct.getQuantity() + "개";
    }
}
