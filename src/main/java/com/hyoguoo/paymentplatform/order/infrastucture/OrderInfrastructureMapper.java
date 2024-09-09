package com.hyoguoo.paymentplatform.order.infrastucture;

import com.hyoguoo.paymentplatform.order.application.dto.request.TossCancelInfo;
import com.hyoguoo.paymentplatform.order.application.dto.request.TossConfirmInfo;
import com.hyoguoo.paymentplatform.order.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.TossCancelClientRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.request.TossConfirmClientRequest;
import com.hyoguoo.paymentplatform.payment.presentation.dto.response.TossDetailsClientResponse;
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
}
