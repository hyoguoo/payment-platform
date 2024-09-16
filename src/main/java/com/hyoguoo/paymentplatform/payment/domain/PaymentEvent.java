package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import java.time.LocalDateTime;
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

    @Builder(builderMethodName = "requiredBuilder", buildMethodName = "requiredBuild")
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
}
