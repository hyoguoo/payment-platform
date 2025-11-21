package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.core.common.aspect.annotation.PublishDomainEvent;
import com.hyoguoo.paymentplatform.core.common.aspect.annotation.Reason;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.core.common.metrics.annotation.PaymentStatusChange;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayPort;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentGatewayInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentCommandUseCase {

    private final PaymentEventRepository paymentEventRepository;
    private final PaymentGatewayPort paymentGatewayPort;
    private final LocalDateTimeProvider localDateTimeProvider;

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "IN_PROGRESS", trigger = "confirm")
    public PaymentEvent executePayment(PaymentEvent paymentEvent, String paymentKey) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.execute(paymentKey, now, now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "DONE", trigger = "auto")
    public PaymentEvent markPaymentAsDone(PaymentEvent paymentEvent, LocalDateTime approvedAt) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.done(approvedAt, now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "FAILED", trigger = "auto")
    public PaymentEvent markPaymentAsFail(PaymentEvent paymentEvent, @Reason String failureReason) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.fail(failureReason, now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "UNKNOWN", trigger = "auto")
    public PaymentEvent markPaymentAsUnknown(PaymentEvent paymentEvent, @Reason String reason) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.unknown(reason, now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishDomainEvent(action = "retry")
    public PaymentEvent increaseRetryCount(PaymentEvent paymentEvent) {
        paymentEvent.increaseRetryCount();
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "EXPIRED", trigger = "expiration")
    public PaymentEvent expirePayment(PaymentEvent paymentEvent) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.expire(now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    public void validateCompletionStatus(
            PaymentEvent paymentEvent,
            PaymentConfirmCommand paymentConfirmCommand
    ) {
        com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult statusResult =
                paymentGatewayPort.getStatus(paymentConfirmCommand.getPaymentKey());

        PaymentGatewayInfo paymentGatewayInfo = PaymentGatewayInfo.builder()
                .paymentKey(statusResult.paymentKey())
                .orderId(statusResult.orderId())
                .paymentConfirmResultStatus(
                        statusResult.status() == com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentStatus.DONE ?
                                PaymentConfirmResultStatus.SUCCESS :
                                (statusResult.failure() != null && statusResult.failure().isRetryable() ?
                                        PaymentConfirmResultStatus.RETRYABLE_FAILURE :
                                        PaymentConfirmResultStatus.NON_RETRYABLE_FAILURE)
                )
                .paymentDetails(
                        com.hyoguoo.paymentplatform.payment.domain.dto.vo.PaymentDetails.builder()
                                .totalAmount(statusResult.amount())
                                .status(convertToTossPaymentStatus(statusResult.status()))
                                .approvedAt(statusResult.approvedAt())
                                .build()
                )
                .paymentFailure(
                        statusResult.failure() != null ?
                                com.hyoguoo.paymentplatform.payment.domain.dto.vo.PaymentFailure.builder()
                                        .code(statusResult.failure().code())
                                        .message(statusResult.failure().message())
                                        .build() : null
                )
                .build();

        paymentEvent.validateCompletionStatus(paymentConfirmCommand, paymentGatewayInfo);
    }

    public PaymentGatewayInfo confirmPaymentWithGateway(PaymentConfirmCommand paymentConfirmCommand)
            throws PaymentTossRetryableException, PaymentTossNonRetryableException {
        com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest request =
                new com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest(
                        paymentConfirmCommand.getOrderId(),
                        paymentConfirmCommand.getPaymentKey(),
                        paymentConfirmCommand.getAmount()
                );

        com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult result =
                paymentGatewayPort.confirm(request);

        PaymentGatewayInfo paymentGatewayInfo = PaymentGatewayInfo.builder()
                .paymentKey(result.paymentKey())
                .orderId(request.orderId())
                .paymentConfirmResultStatus(result.status())
                .paymentDetails(
                        com.hyoguoo.paymentplatform.payment.domain.dto.vo.PaymentDetails.builder()
                                .totalAmount(result.amount())
                                .status(com.hyoguoo.paymentplatform.payment.domain.dto.enums.TossPaymentStatus.DONE)
                                .approvedAt(result.approvedAt())
                                .build()
                )
                .paymentFailure(
                        result.failure() != null ?
                                com.hyoguoo.paymentplatform.payment.domain.dto.vo.PaymentFailure.builder()
                                        .code(result.failure().code())
                                        .message(result.failure().message())
                                        .build() : null
                )
                .build();

        PaymentConfirmResultStatus paymentConfirmResultStatus = paymentGatewayInfo.getPaymentConfirmResultStatus();

        return switch (paymentConfirmResultStatus) {
            case PaymentConfirmResultStatus.SUCCESS -> paymentGatewayInfo;
            case PaymentConfirmResultStatus.RETRYABLE_FAILURE ->
                    throw PaymentTossRetryableException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR);
            case PaymentConfirmResultStatus.NON_RETRYABLE_FAILURE ->
                    throw PaymentTossNonRetryableException.of(PaymentErrorCode.TOSS_NON_RETRYABLE_ERROR);
        };
    }

    private com.hyoguoo.paymentplatform.payment.domain.dto.enums.TossPaymentStatus convertToTossPaymentStatus(
            com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentStatus status
    ) {
        return switch (status) {
            case DONE -> com.hyoguoo.paymentplatform.payment.domain.dto.enums.TossPaymentStatus.DONE;
            case IN_PROGRESS -> com.hyoguoo.paymentplatform.payment.domain.dto.enums.TossPaymentStatus.IN_PROGRESS;
            case WAITING_FOR_DEPOSIT -> com.hyoguoo.paymentplatform.payment.domain.dto.enums.TossPaymentStatus.WAITING_FOR_DEPOSIT;
            case CANCELED -> com.hyoguoo.paymentplatform.payment.domain.dto.enums.TossPaymentStatus.CANCELED;
            case PARTIAL_CANCELED -> com.hyoguoo.paymentplatform.payment.domain.dto.enums.TossPaymentStatus.PARTIAL_CANCELED;
            case ABORTED -> com.hyoguoo.paymentplatform.payment.domain.dto.enums.TossPaymentStatus.ABORTED;
            case EXPIRED -> com.hyoguoo.paymentplatform.payment.domain.dto.enums.TossPaymentStatus.EXPIRED;
            case READY -> com.hyoguoo.paymentplatform.payment.domain.dto.enums.TossPaymentStatus.READY;
            default -> com.hyoguoo.paymentplatform.payment.domain.dto.enums.TossPaymentStatus.READY; // UNKNOWN -> READY로 매핑
        };
    }
}
