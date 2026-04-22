package com.hyoguoo.paymentplatform.payment.infrastructure;

import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderedProductStockCommand;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductInfoResponse;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductStockRequest;
import com.hyoguoo.paymentplatform.user.presentation.dto.UserInfoResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentInfrastructureMapper {

    public static ProductInfo toProductInfo(ProductInfoResponse productInfoResponse) {
        return ProductInfo.builder()
                .id(productInfoResponse.getId())
                .name(productInfoResponse.getName())
                .price(productInfoResponse.getPrice())
                .stock(productInfoResponse.getStock())
                .sellerId(productInfoResponse.getSellerId())
                .build();
    }

    public static UserInfo toUserInfo(UserInfoResponse userInfoResponse) {
        return UserInfo.builder()
                .id(userInfoResponse.getId())
                .build();
    }

    public static ProductStockRequest toProductStockRequest(OrderedProductStockCommand orderedProductStockCommand) {
        return ProductStockRequest.builder()
                .productId(orderedProductStockCommand.getProductId())
                .stock(orderedProductStockCommand.getStock())
                .build();
    }
}
