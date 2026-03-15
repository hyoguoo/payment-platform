package com.hyoguoo.paymentplatform.payment.listener;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentGatewayInfo;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaConfirmListener {

    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentCommandUseCase paymentCommandUseCase;
    private final PaymentTransactionCoordinator transactionCoordinator;

    @RetryableTopic(
            attempts = "6",
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 30000),
            dltTopicSuffix = "-dlq",
            include = {PaymentTossRetryableException.class},
            exclude = {PaymentTossNonRetryableException.class},
            autoCreateTopics = "true"
    )
    @KafkaListener(
            topics = "payment-confirm",
            groupId = "${spring.kafka.consumer.group-id:payment-confirm-group}"
    )
    public void consume(String orderId) throws PaymentTossRetryableException {
        PaymentEvent paymentEvent =
                paymentLoadUseCase.getPaymentEventByOrderId(orderId);

        PaymentConfirmCommand command = PaymentConfirmCommand.builder()
                .userId(paymentEvent.getBuyerId())
                .orderId(orderId)
                .paymentKey(paymentEvent.getPaymentKey())
                .amount(paymentEvent.getTotalAmount())
                .build();

        try {
            PaymentGatewayInfo gatewayInfo =
                    paymentCommandUseCase.confirmPaymentWithGateway(command);

            transactionCoordinator.executePaymentSuccessCompletion(
                    orderId, paymentEvent,
                    gatewayInfo.getPaymentDetails().getApprovedAt()
            );

        } catch (PaymentTossNonRetryableException e) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);
            transactionCoordinator.executePaymentFailureCompensation(
                    orderId, paymentEvent, paymentEvent.getPaymentOrderList(), e.getMessage()
            );

        } catch (PaymentTossRetryableException e) {
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);
            throw e;
        }
    }

    @DltHandler
    public void handleDlt(String orderId) {
        LogFmt.error(log, LogDomain.PAYMENT, EventType.EXCEPTION,
                () -> "DLT reached for orderId=" + orderId);
        PaymentEvent paymentEvent =
                paymentLoadUseCase.getPaymentEventByOrderId(orderId);
        transactionCoordinator.executePaymentFailureCompensation(
                orderId, paymentEvent, paymentEvent.getPaymentOrderList(),
                "kafka-dlt-exhausted"
        );
    }
}
