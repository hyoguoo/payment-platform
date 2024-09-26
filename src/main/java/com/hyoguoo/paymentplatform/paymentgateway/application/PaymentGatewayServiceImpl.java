package com.hyoguoo.paymentplatform.paymentgateway.application;

import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.port.TossOperator;
import com.hyoguoo.paymentplatform.paymentgateway.domain.TossPaymentInfo;
import com.hyoguoo.paymentplatform.paymentgateway.domain.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.paymentgateway.domain.vo.TossPaymentFailure;
import com.hyoguoo.paymentplatform.paymentgateway.exception.PaymentGatewayApiException;
import com.hyoguoo.paymentplatform.paymentgateway.exception.common.TossPaymentErrorCode;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.port.PaymentGatewayService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentGatewayServiceImpl implements PaymentGatewayService {

    private final TossOperator tossOperator;

    @Override
    public TossPaymentInfo getPaymentResultByOrderId(String orderId) {
        return tossOperator.findPaymentInfoByOrderId(orderId);
    }

    @Override
    public TossPaymentInfo confirmPayment(
            TossConfirmCommand tossConfirmCommand,
            String idempotencyKey
    ) {
        try {
            return tossOperator.confirmPayment(tossConfirmCommand, idempotencyKey);
        } catch (PaymentGatewayApiException e) {
            return handlePaymentGateApiException(e);
        }
    }

    @Override
    public TossPaymentInfo cancelPayment(
            TossCancelCommand tossCancelCommand,
            String idempotencyKey
    ) {
        return tossOperator.cancelPayment(
                tossCancelCommand,
                idempotencyKey
        );
    }

    private TossPaymentInfo handlePaymentGateApiException(PaymentGatewayApiException e) {
        TossPaymentErrorCode tossPaymentErrorCode = TossPaymentErrorCode.of(e.getCode());
        PaymentConfirmResultStatus paymentConfirmResultStatus = PaymentConfirmResultStatus.of(
                tossPaymentErrorCode
        );
        TossPaymentFailure paymentFailure = TossPaymentFailure.builder()
                .code(tossPaymentErrorCode.name())
                .message(tossPaymentErrorCode.getDescription())
                .build();

        return TossPaymentInfo.builder()
                .paymentConfirmResultStatus(paymentConfirmResultStatus)
                .paymentFailure(paymentFailure)
                .build();
    }
}
