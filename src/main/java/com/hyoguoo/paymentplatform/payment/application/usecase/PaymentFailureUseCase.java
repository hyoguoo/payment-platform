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

    private final PaymentProcessorUseCase paymentProcessorUseCase;
    private final OrderedProductUseCase orderedProductUseCase;

    public PaymentEvent handleStockFailure(PaymentEvent paymentEvent, String failureMessage) {
        PaymentEvent failedPaymentEvent = paymentProcessorUseCase.markPaymentAsFail(paymentEvent, failureMessage);
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_STATUS_TO_FAIL,
                () -> String.format("orderId=%s reason=%s", failedPaymentEvent.getOrderId(), failureMessage));
        return failedPaymentEvent;
    }

    public PaymentEvent handleNonRetryableFailure(PaymentEvent paymentEvent, String failureMessage) {
        PaymentEvent failedPaymentEvent = paymentProcessorUseCase.markPaymentAsFail(paymentEvent, failureMessage);
        LogFmt.error(log, LogDomain.PAYMENT, EventType.PAYMENT_STATUS_TO_FAIL,
                () -> String.format("orderId=%s reason=%s", paymentEvent.getOrderId(), failureMessage));

        orderedProductUseCase.increaseStockForOrders(failedPaymentEvent.getPaymentOrderList());
        LogFmt.info(log, LogDomain.PAYMENT, EventType.STOCK_INCREASE_REQUEST,
                () -> String.format("products=%s by orderId=%s",
                        LogFmt.toJson(failedPaymentEvent.getPaymentOrderList()),
                        failedPaymentEvent.getOrderId()));
        return failedPaymentEvent;
    }

    public PaymentEvent handleRetryableFailure(PaymentEvent paymentEvent, String failureMessage) {
        PaymentEvent unknownPaymentEvent = paymentProcessorUseCase.markPaymentAsUnknown(paymentEvent, failureMessage);
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.PAYMENT_STATUS_TO_UNKNOWN,
                () -> String.format("orderId=%s reason=%s", paymentEvent.getOrderId(), failureMessage));
        return unknownPaymentEvent;
    }

    public PaymentEvent handleUnknownFailure(PaymentEvent paymentEvent, String failureMessage) {
        String message = failureMessage != null ? failureMessage : "Unknown error occurred";
        PaymentEvent failedPaymentEvent = paymentProcessorUseCase.markPaymentAsFail(paymentEvent, message);
        LogFmt.error(log, LogDomain.PAYMENT, EventType.PAYMENT_STATUS_TO_FAIL,
                () -> String.format("orderId=%s reason=%s", paymentEvent.getOrderId(), message));

        orderedProductUseCase.increaseStockForOrders(failedPaymentEvent.getPaymentOrderList());
        LogFmt.error(log, LogDomain.PAYMENT, EventType.STOCK_INCREASE_REQUEST,
                () -> String.format("products=%s by orderId=%s",
                        LogFmt.toJson(failedPaymentEvent.getPaymentOrderList()),
                        failedPaymentEvent.getOrderId()));
        return failedPaymentEvent;
    }
}
