package com.hyoguoo.paymentplatform.paymentgateway.infrastructure;

import com.hyoguoo.paymentplatform.paymentgateway.domain.TossPaymentInfo;
import com.hyoguoo.paymentplatform.paymentgateway.domain.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.paymentgateway.domain.enums.TossPaymentStatus;
import com.hyoguoo.paymentplatform.paymentgateway.domain.vo.TossPaymentDetails;
import com.hyoguoo.paymentplatform.paymentgateway.infrastructure.dto.response.TossPaymentApiResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentGatewayInfrastructureMapper {

    public static TossPaymentInfo toSuccessTossPaymentInfo(
            TossPaymentApiResponse tossPaymentResponse
    ) {
        LocalDateTime approvedAt = tossPaymentResponse.getApprovedAt() == null
                ? null
                : LocalDateTime.parse(
                        tossPaymentResponse.getApprovedAt(),
                        TossPaymentApiResponse.DATE_TIME_FORMATTER
                );

        TossPaymentStatus status = TossPaymentStatus.of(tossPaymentResponse.getStatus());

        TossPaymentDetails paymentDetails = TossPaymentDetails.builder()
                .orderName(tossPaymentResponse.getOrderName())
                .totalAmount(BigDecimal.valueOf(tossPaymentResponse.getTotalAmount()))
                .status(status)
                .approvedAt(approvedAt)
                .rawData(tossPaymentResponse.toString())
                .build();

        return TossPaymentInfo.builder()
                .paymentKey(tossPaymentResponse.getPaymentKey())
                .orderId(tossPaymentResponse.getOrderId())
                .paymentConfirmResultStatus(PaymentConfirmResultStatus.SUCCESS)
                .paymentDetails(paymentDetails)
                .build();
    }
}
