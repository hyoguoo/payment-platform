package com.hyoguoo.paymentplatform.paymentgateway.application;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossCancelCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.dto.request.TossConfirmCommand;
import com.hyoguoo.paymentplatform.paymentgateway.application.port.TossOperator;
import com.hyoguoo.paymentplatform.paymentgateway.application.usecase.TossApiCallUseCase;
import com.hyoguoo.paymentplatform.paymentgateway.application.usecase.TossApiFailureUseCase;
import com.hyoguoo.paymentplatform.paymentgateway.domain.TossPaymentInfo;
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
    private final TossApiCallUseCase tossApiCallUseCase;
    private final TossApiFailureUseCase tossApiFailureUseCase;

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
            TossPaymentInfo confirmPayment = tossApiCallUseCase.executeConfirmPayment(
                    tossConfirmCommand,
                    idempotencyKey);

            LogFmt.info(log, LogDomain.PAYMENT_GATEWAY, EventType.PAYMENT_GATEWAY_CONFIRM_SUCCESS,
                    () -> String.format("orderId=%s", tossConfirmCommand.getOrderId()));
            return confirmPayment;
        } catch (PaymentGatewayApiException e) {
            return handleApiException(tossConfirmCommand.getOrderId(), e);
        }
    }

    private TossPaymentInfo handleApiException(String orderId, PaymentGatewayApiException e) {
        TossPaymentErrorCode errorCode = TossPaymentErrorCode.of(e.getCode());

        LogFmt.warn(log, LogDomain.PAYMENT_GATEWAY, EventType.PAYMENT_GATEWAY_CONFIRM_FAIL,
                () -> String.format("orderId=%s errorCode=%s errorMessage=%s",
                        orderId, errorCode.name(), errorCode.getDescription()));

        return tossApiFailureUseCase.handleTossApiFailure(errorCode);
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
}
