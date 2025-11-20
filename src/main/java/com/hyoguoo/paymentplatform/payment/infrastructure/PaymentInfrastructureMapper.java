package com.hyoguoo.paymentplatform.payment.infrastructure;

import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderedProductStockCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossCancelGatewayCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmGatewayCommand;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentGatewayInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.UserInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.TossPaymentStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.vo.PaymentDetails;
import com.hyoguoo.paymentplatform.payment.domain.dto.vo.PaymentFailure;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.TossCancelRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.TossConfirmRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response.TossPaymentResponse;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductInfoResponse;
import com.hyoguoo.paymentplatform.product.presentation.dto.ProductStockRequest;
import com.hyoguoo.paymentplatform.user.presentation.dto.UserInfoResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentInfrastructureMapper {

    public static PaymentGatewayInfo toPaymentGatewayInfo(
            TossPaymentResponse tossPaymentResponse
    ) {
        PaymentDetails paymentDetails = tossPaymentResponse.getPaymentDetails() != null
                ? PaymentDetails.builder()
                .totalAmount(tossPaymentResponse.getPaymentDetails().getTotalAmount())
                .status(TossPaymentStatus.of(
                        tossPaymentResponse.getPaymentDetails().getStatus().getValue())
                )
                .approvedAt(tossPaymentResponse.getPaymentDetails().getApprovedAt())
                .rawData(tossPaymentResponse.getPaymentDetails().getRawData())
                .build()
                : null;

        PaymentFailure paymentFailure = tossPaymentResponse.getPaymentFailure() != null
                ? PaymentFailure.builder()
                .code(tossPaymentResponse.getPaymentFailure().getCode())
                .message(tossPaymentResponse.getPaymentFailure().getMessage())
                .build()
                : null;

        PaymentConfirmResultStatus paymentConfirmResultStatus = PaymentConfirmResultStatus.of(
                tossPaymentResponse.getPaymentConfirmResultStatus().getValue()
        );

        return PaymentGatewayInfo.builder()
                .paymentKey(tossPaymentResponse.getPaymentKey())
                .orderId(tossPaymentResponse.getOrderId())
                .paymentConfirmResultStatus(paymentConfirmResultStatus)
                .paymentDetails(paymentDetails)
                .paymentFailure(paymentFailure)
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
