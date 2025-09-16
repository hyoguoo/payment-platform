package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmGatewayCommand;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayPort;
import com.hyoguoo.paymentplatform.payment.application.publisher.PaymentEventPublisher;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentProcessorUseCase {

    private final PaymentEventRepository paymentEventRepository;
    private final PaymentGatewayPort paymentGatewayPort;
    private final LocalDateTimeProvider localDateTimeProvider;
    private final PaymentEventPublisher paymentEventPublisher;

    @Transactional
    public PaymentEvent executePayment(PaymentEvent paymentEvent, String paymentKey) {
        PaymentEventStatus previousStatus = paymentEvent.getStatus();
        LocalDateTime executedAt = localDateTimeProvider.now();

        paymentEvent.execute(paymentKey, executedAt);
        PaymentEvent savedEvent = paymentEventRepository.saveOrUpdate(paymentEvent);

        paymentEventPublisher.publishStatusChange(
                savedEvent,
                previousStatus,
                "Payment execution started with payment key: " + paymentKey,
                executedAt
        );

        return savedEvent;
    }

    @Transactional
    public PaymentEvent markPaymentAsDone(PaymentEvent paymentEvent, LocalDateTime approvedAt) {
        PaymentEventStatus previousStatus = paymentEvent.getStatus();
        LocalDateTime occurredAt = localDateTimeProvider.now();

        paymentEvent.done(approvedAt);
        PaymentEvent savedEvent = paymentEventRepository.saveOrUpdate(paymentEvent);

        paymentEventPublisher.publishStatusChange(
                savedEvent,
                previousStatus,
                "Payment successfully completed at " + approvedAt,
                occurredAt
        );

        return savedEvent;
    }

    @Transactional
    public PaymentEvent markPaymentAsFail(PaymentEvent paymentEvent, String failureReason) {
        PaymentEventStatus previousStatus = paymentEvent.getStatus();
        LocalDateTime occurredAt = localDateTimeProvider.now();

        paymentEvent.fail();
        PaymentEvent savedEvent = paymentEventRepository.saveOrUpdate(paymentEvent);

        paymentEventPublisher.publishStatusChange(
                savedEvent,
                previousStatus,
                failureReason,
                occurredAt
        );

        return savedEvent;
    }

    @Transactional
    public PaymentEvent markPaymentAsUnknown(PaymentEvent paymentEvent, String reason) {
        PaymentEventStatus previousStatus = paymentEvent.getStatus();
        LocalDateTime occurredAt = localDateTimeProvider.now();

        paymentEvent.unknown();
        PaymentEvent savedEvent = paymentEventRepository.saveOrUpdate(paymentEvent);

        paymentEventPublisher.publishStatusChange(
                savedEvent,
                previousStatus,
                reason,
                occurredAt
        );

        return savedEvent;
    }

    @Transactional
    public void increaseRetryCount(PaymentEvent paymentEvent, String retryReason) {
        PaymentEventStatus previousStatus = paymentEvent.getStatus();
        LocalDateTime occurredAt = localDateTimeProvider.now();

        paymentEvent.increaseRetryCount();
        PaymentEvent savedEvent = paymentEventRepository.saveOrUpdate(paymentEvent);

        paymentEventPublisher.publishRetryAttempt(
                savedEvent,
                previousStatus,
                retryReason,
                occurredAt
        );
    }

    public void validateCompletionStatus(
            PaymentEvent paymentEvent,
            PaymentConfirmCommand paymentConfirmCommand
    ) {
        TossPaymentInfo tossPaymentInfo = paymentGatewayPort.getPaymentInfoByOrderId(
                paymentConfirmCommand.getOrderId()
        );

        paymentEvent.validateCompletionStatus(paymentConfirmCommand, tossPaymentInfo);
    }

    public TossPaymentInfo confirmPaymentWithGateway(PaymentConfirmCommand paymentConfirmCommand)
            throws PaymentTossRetryableException, PaymentTossNonRetryableException {
        TossConfirmGatewayCommand tossConfirmGatewayCommand = TossConfirmGatewayCommand.builder()
                .orderId(paymentConfirmCommand.getOrderId())
                .paymentKey(paymentConfirmCommand.getPaymentKey())
                .amount(paymentConfirmCommand.getAmount())
                .idempotencyKey(paymentConfirmCommand.getOrderId())
                .build();

        TossPaymentInfo tossPaymentInfo = paymentGatewayPort.confirmPayment(
                tossConfirmGatewayCommand
        );

        PaymentConfirmResultStatus paymentConfirmResultStatus = tossPaymentInfo.getPaymentConfirmResultStatus();

        return switch (paymentConfirmResultStatus) {
            case PaymentConfirmResultStatus.SUCCESS -> tossPaymentInfo;
            case PaymentConfirmResultStatus.RETRYABLE_FAILURE ->
                    throw PaymentTossRetryableException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR);
            case PaymentConfirmResultStatus.NON_RETRYABLE_FAILURE ->
                    throw PaymentTossNonRetryableException.of(PaymentErrorCode.TOSS_NON_RETRYABLE_ERROR);
        };
    }
}
