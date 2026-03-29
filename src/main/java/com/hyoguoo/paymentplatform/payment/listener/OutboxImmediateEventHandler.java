package com.hyoguoo.paymentplatform.payment.listener;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentGatewayInfo;
import java.util.Optional;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentConfirmEvent;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxImmediateEventHandler {

    private final PaymentOutboxUseCase paymentOutboxUseCase;
    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentCommandUseCase paymentCommandUseCase;
    private final PaymentTransactionCoordinator transactionCoordinator;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PaymentConfirmEvent event) {
        String orderId = event.getOrderId();

        Optional<PaymentOutbox> outboxOpt = paymentOutboxUseCase.findByOrderId(orderId);
        if (outboxOpt.isEmpty()) {
            return;
        }
        PaymentOutbox outbox = outboxOpt.get();

        boolean claimed = paymentOutboxUseCase.claimToInFlight(outbox);
        if (!claimed) {
            return;
        }

        Optional<PaymentEvent> paymentEventOpt = loadPaymentEvent(orderId, outbox);
        if (paymentEventOpt.isEmpty()) {
            return;
        }
        PaymentEvent paymentEvent = paymentEventOpt.get();

        PaymentConfirmCommand command = PaymentConfirmCommand.builder()
                .userId(paymentEvent.getBuyerId())
                .orderId(orderId)
                .paymentKey(paymentEvent.getPaymentKey())
                .amount(paymentEvent.getTotalAmount())
                .build();

        try {
            PaymentGatewayInfo gatewayInfo = paymentCommandUseCase.confirmPaymentWithGateway(command);

            transactionCoordinator.executePaymentSuccessCompletionWithOutbox(
                    paymentEvent, gatewayInfo.getPaymentDetails().getApprovedAt(), outbox);

        } catch (PaymentTossNonRetryableException e) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);
            transactionCoordinator.executePaymentFailureCompensationWithOutbox(
                    paymentEvent, paymentEvent.getPaymentOrderList(), e.getMessage(), outbox);

        } catch (PaymentTossRetryableException e) {
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);
            boolean exhausted = paymentOutboxUseCase.incrementRetryOrFail(orderId, outbox);
            if (exhausted) {
                transactionCoordinator.executePaymentFailureCompensationWithOutbox(
                        paymentEvent, paymentEvent.getPaymentOrderList(), e.getMessage(), outbox);
            }
        } catch (Exception e) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);
            boolean exhausted = paymentOutboxUseCase.incrementRetryOrFail(orderId, outbox);
            if (exhausted) {
                transactionCoordinator.executePaymentFailureCompensationWithOutbox(
                        paymentEvent, paymentEvent.getPaymentOrderList(), e.getMessage(), outbox);
            }
        }
    }

    private Optional<PaymentEvent> loadPaymentEvent(String orderId, PaymentOutbox outbox) {
        try {
            return Optional.of(paymentLoadUseCase.getPaymentEventByOrderId(orderId));
        } catch (Exception e) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);
            paymentOutboxUseCase.incrementRetryOrFail(orderId, outbox);
            return Optional.empty();
        }
    }
}
