package com.hyoguoo.paymentplatform.payment.presentation;

import com.hyoguoo.paymentplatform.payment.application.dto.request.CheckoutCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.CheckoutRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.CheckoutResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentConfirmResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentStatusApiResponse;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.PaymentStatusResponse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentPresentationMapper {

    public static CheckoutCommand toCheckoutCommand(CheckoutRequest request) {
        return CheckoutCommand.builder()
                .userId(request.getUserId())
                .orderedProductList(request.getOrderedProductList())
                .build();
    }

    public static CheckoutResponse toCheckoutResponse(CheckoutResult result) {
        return CheckoutResponse.builder()
                .orderId(result.getOrderId())
                .totalAmount(result.getTotalAmount())
                .build();
    }

    public static PaymentConfirmCommand toPaymentConfirmCommand(
            PaymentConfirmRequest paymentConfirmRequest
    ) {
        return PaymentConfirmCommand.builder()
                .userId(paymentConfirmRequest.getUserId())
                .orderId(paymentConfirmRequest.getOrderId())
                .paymentKey(paymentConfirmRequest.getPaymentKey())
                .amount(paymentConfirmRequest.getAmount())
                .build();
    }

    public static PaymentConfirmResponse toPaymentConfirmResponse(
            PaymentConfirmAsyncResult result
    ) {
        return PaymentConfirmResponse.builder()
                .orderId(result.getOrderId())
                .amount(result.getAmount())
                .build();
    }

    public static PaymentStatusApiResponse toPaymentStatusApiResponse(PaymentEvent event) {
        return PaymentStatusApiResponse.builder()
                .orderId(event.getOrderId())
                .status(mapToPaymentStatusResponse(event.getStatus()))
                .approvedAt(event.getApprovedAt())
                .build();
    }

    public static PaymentStatusApiResponse toPaymentStatusApiResponseFromOutbox(
            String orderId, PaymentOutboxStatus outboxStatus) {
        return PaymentStatusApiResponse.builder()
                .orderId(orderId)
                .status(mapOutboxStatusToPaymentStatusResponse(outboxStatus))
                .approvedAt(null)
                .build();
    }

    private static PaymentStatusResponse mapToPaymentStatusResponse(PaymentEventStatus status) {
        return switch (status) {
            case DONE -> PaymentStatusResponse.DONE;
            case FAILED -> PaymentStatusResponse.FAILED;
            default -> PaymentStatusResponse.PROCESSING;
        };
    }

    private static PaymentStatusResponse mapOutboxStatusToPaymentStatusResponse(
            PaymentOutboxStatus status) {
        return switch (status) {
            case PENDING -> PaymentStatusResponse.PENDING;
            case IN_FLIGHT -> PaymentStatusResponse.PROCESSING;
            default -> PaymentStatusResponse.PROCESSING;
        };
    }
}
