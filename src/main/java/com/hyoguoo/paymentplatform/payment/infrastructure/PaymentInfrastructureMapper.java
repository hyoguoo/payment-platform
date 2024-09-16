package com.hyoguoo.paymentplatform.payment.infrastructure;

import com.hyoguoo.paymentplatform.payment.application.dto.request.TossCancelGatewayCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmGatewayCommand;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.TossCancelRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.TossConfirmRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response.TossPaymentResponse;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductInfoResponse;
import com.hyoguoo.paymentplatform.user.presentation.dto.UserInfoResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentInfrastructureMapper {

    public static TossPaymentInfo toTossPaymentInfo(
            TossPaymentResponse tossPaymentResponse
    ) {
        return TossPaymentInfo.builder()
                .paymentKey(tossPaymentResponse.getPaymentKey())
                .orderName(tossPaymentResponse.getOrderName())
                .method(tossPaymentResponse.getMethod())
                .totalAmount(tossPaymentResponse.getTotalAmount())
                .status(tossPaymentResponse.getStatus())
                .requestedAt(tossPaymentResponse.getRequestedAt())
                .approvedAt(tossPaymentResponse.getApprovedAt())
                .lastTransactionKey(tossPaymentResponse.getLastTransactionKey())
                .build();
    }

    public static TossConfirmRequest toTossConfirmRequest(
            TossConfirmGatewayCommand tossConfirmGatewayCommand
    ) {
        return TossConfirmRequest.builder()
                .orderId(tossConfirmGatewayCommand.getOrderId())
                .amount(tossConfirmGatewayCommand.getAmount())
                .paymentKey(tossConfirmGatewayCommand.getPaymentKey())
                .idempotencyKey(tossConfirmGatewayCommand.getIdempotencyKey())
                .build();
    }

    public static TossCancelRequest toTossCancelRequest(
            TossCancelGatewayCommand tossCancelGatewayCommand
    ) {
        return TossCancelRequest.builder()
                .cancelReason(tossCancelGatewayCommand.getCancelReason())
                .paymentKey(tossCancelGatewayCommand.getPaymentKey())
                .idempotencyKey(tossCancelGatewayCommand.getIdempotencyKey())
                .build();
    }

    public static ProductInfo toProductInfo(ProductInfoResponse productInfoResponse) {
        return ProductInfo.builder()
                .id(productInfoResponse.getId())
                .name(productInfoResponse.getName())
                .price(productInfoResponse.getPrice())
                .stock(productInfoResponse.getStock())
                .build();
    }

    public static UserInfo toUserInfo(UserInfoResponse userInfoResponse) {
        return UserInfo.builder()
                .id(userInfoResponse.getId())
                .build();
    }
}
