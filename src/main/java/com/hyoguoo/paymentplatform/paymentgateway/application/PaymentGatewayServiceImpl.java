package com.hyoguoo.paymentplatform.paymentgateway.application;

import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.core.common.log.EventType;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
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
            LogFmt.info(log, LogDomain.PAYMENT_GATEWAY, EventType.PAYMENT_GATEWAY_CONFIRM_REQUEST,
                    () -> String.format("orderId=%s", tossConfirmCommand.getOrderId()));
            TossPaymentInfo confirmPayment = tossOperator.confirmPayment(tossConfirmCommand, idempotencyKey);
            LogFmt.info(log, LogDomain.PAYMENT_GATEWAY, EventType.PAYMENT_GATEWAY_CONFIRM_SUCCESS,
                    () -> String.format("orderId=%s", tossConfirmCommand.getOrderId()));
            return confirmPayment;
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
        LogFmt.warn(log, LogDomain.PAYMENT_GATEWAY, EventType.PAYMENT_GATEWAY_CONFIRM_FAIL,
                () -> String.format("errorCode=%s errorMessage=%s",
                        tossPaymentErrorCode.name(), tossPaymentErrorCode.getDescription()));

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
