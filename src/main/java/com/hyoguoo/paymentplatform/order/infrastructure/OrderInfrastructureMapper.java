package com.hyoguoo.paymentplatform.order.infrastructure;

import com.hyoguoo.paymentplatform.order.application.dto.request.TossCancelInfo;
import com.hyoguoo.paymentplatform.order.application.dto.request.TossConfirmInfo;
import com.hyoguoo.paymentplatform.order.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.order.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.order.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.TossCancelClientRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.TossConfirmClientRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response.TossDetailsClientResponse;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductInfoClientResponse;
import com.hyoguoo.paymentplatform.user.presentation.dto.UserInfoClientResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class OrderInfrastructureMapper {

    public static TossPaymentInfo toTossPaymentInfo(
            TossDetailsClientResponse tossDetailsClientResponse
    ) {
        return TossPaymentInfo.builder()
                .paymentKey(tossDetailsClientResponse.getPaymentKey())
                .orderName(tossDetailsClientResponse.getOrderName())
                .method(tossDetailsClientResponse.getMethod())
                .totalAmount(tossDetailsClientResponse.getTotalAmount())
                .status(tossDetailsClientResponse.getStatus())
                .requestedAt(tossDetailsClientResponse.getRequestedAt())
                .approvedAt(tossDetailsClientResponse.getApprovedAt())
                .lastTransactionKey(tossDetailsClientResponse.getLastTransactionKey())
                .build();
    }

    public static TossConfirmClientRequest toTossConfirmRequest(TossConfirmInfo tossConfirmInfo) {
        return TossConfirmClientRequest.builder()
                .orderId(tossConfirmInfo.getOrderId())
                .amount(tossConfirmInfo.getAmount())
                .paymentKey(tossConfirmInfo.getPaymentKey())
                .idempotencyKey(tossConfirmInfo.getIdempotencyKey())
                .build();
    }

    public static TossCancelClientRequest toTossCancelRequest(
            TossCancelInfo tossConfirmInfo
    ) {
        return TossCancelClientRequest.builder()
                .cancelReason(tossConfirmInfo.getCancelReason())
                .paymentKey(tossConfirmInfo.getPaymentKey())
                .idempotencyKey(tossConfirmInfo.getIdempotencyKey())
                .build();
    }

    public static ProductInfo toProductInfo(ProductInfoClientResponse productInfoClientResponse) {
        return ProductInfo.builder()
                .id(productInfoClientResponse.getId())
                .name(productInfoClientResponse.getName())
                .price(productInfoClientResponse.getPrice())
                .stock(productInfoClientResponse.getStock())
                .build();
    }

    public static UserInfo toUserInfo(UserInfoClientResponse userInfoClientResponse) {
        return UserInfo.builder()
                .id(userInfoClientResponse.getId())
                .build();
    }
}
