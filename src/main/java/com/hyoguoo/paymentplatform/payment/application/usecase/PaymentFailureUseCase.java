package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentFailureUseCase {

    private final PaymentCommandUseCase paymentCommandUseCase;

    public PaymentEvent handleStockFailure(PaymentEvent paymentEvent, String failureMessage) {
        PaymentEvent failedPaymentEvent = paymentCommandUseCase.markPaymentAsFail(paymentEvent, failureMessage);
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_STATUS_TO_FAIL,
                () -> String.format("orderId=%s reason=%s", failedPaymentEvent.getOrderId(), failureMessage));
        return failedPaymentEvent;
    }

    public PaymentEvent handleRetryableFailure(PaymentEvent paymentEvent, String failureMessage) {
        PaymentEvent unknownPaymentEvent = paymentCommandUseCase.markPaymentAsUnknown(paymentEvent, failureMessage);
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.PAYMENT_STATUS_TO_UNKNOWN,
                () -> String.format("orderId=%s reason=%s", paymentEvent.getOrderId(), failureMessage));
        return unknownPaymentEvent;
    }
}
