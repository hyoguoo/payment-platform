package com.hyoguoo.paymentplatform.mock;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.port.TossOperator;
import com.hyoguoo.paymentplatform.paymentgateway.domain.TossPaymentInfo;
import com.hyoguoo.paymentplatform.paymentgateway.domain.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.paymentgateway.domain.enums.TossPaymentStatus;
import com.hyoguoo.paymentplatform.paymentgateway.domain.vo.TossPaymentDetails;
import com.hyoguoo.paymentplatform.paymentgateway.exception.PaymentGatewayApiException;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public class FakeTossOperator implements TossOperator {

    private boolean shouldFail;
    private String errorCode;
    private String description;

    public void setErrorCode(boolean shouldFail, String errorCode, String description) {
        this.shouldFail = shouldFail;
        this.errorCode = errorCode;
        this.description = description;
    }

    @Override
    public TossPaymentInfo findPaymentInfoByOrderId(String orderId) {
        return TossPaymentInfo.builder()
                .paymentKey("validPaymentKey")
                .orderId(orderId)
                .paymentConfirmResultStatus(PaymentConfirmResultStatus.SUCCESS)
                .paymentDetails(TossPaymentDetails.builder()
                        .orderName("Test Order")
                        .totalAmount(new BigDecimal("10000"))
                        .status(TossPaymentStatus.DONE)
                        .approvedAt(LocalDateTime.now())
                        .rawData("Raw payment data")
                        .build())
                .paymentFailure(null)
                .build();
    }

    @Override
    public TossPaymentInfo findPaymentInfoByPaymentKey(String paymentKey) {
        return TossPaymentInfo.builder()
                .paymentKey(paymentKey)
                .orderId("validOrderId")
                .paymentConfirmResultStatus(PaymentConfirmResultStatus.SUCCESS)
                .paymentDetails(TossPaymentDetails.builder()
                        .orderName("Test Order")
                        .totalAmount(new BigDecimal("10000"))
                        .status(TossPaymentStatus.DONE)
                        .approvedAt(LocalDateTime.now())
                        .rawData("Raw payment data")
                        .build())
                .paymentFailure(null)
                .build();
    }

    @Override
    public TossPaymentInfo confirmPayment(
            TossConfirmCommand tossConfirmCommand,
            String idempotencyKey
    ) throws PaymentGatewayApiException {
        if (shouldFail) {
            throw PaymentGatewayApiException.of(errorCode, description);
        }
        return TossPaymentInfo.builder()
                .paymentKey("validPaymentKey")
                .orderId(tossConfirmCommand.getOrderId())
                .paymentConfirmResultStatus(PaymentConfirmResultStatus.SUCCESS)
                .paymentDetails(TossPaymentDetails.builder()
                        .orderName("Test Order")
                        .totalAmount(new BigDecimal("10000"))
                        .status(TossPaymentStatus.DONE)
                        .approvedAt(LocalDateTime.now())
                        .rawData("Raw payment data")
                        .build())
                .paymentFailure(null)
                .build();
    }

    @Override
    public TossPaymentInfo cancelPayment(
            TossCancelCommand tossCancelCommand,
            String idempotencyKey
    ) {
        return TossPaymentInfo.builder()
                .paymentKey(tossCancelCommand.getPaymentKey())
                .orderId("order123")
                .paymentConfirmResultStatus(PaymentConfirmResultStatus.SUCCESS)
                .paymentDetails(TossPaymentDetails.builder()
                        .orderName("Test Order")
                        .totalAmount(new BigDecimal("10000"))
                        .status(TossPaymentStatus.CANCELED)
                        .approvedAt(LocalDateTime.now())
                        .rawData("Raw cancellation data")
                        .build())
                .paymentFailure(null)
                .build();
    }
}
