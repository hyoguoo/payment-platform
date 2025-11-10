package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.aspect.PublishPaymentHistory;
import com.hyoguoo.paymentplatform.payment.application.aspect.Reason;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmGatewayCommand;
import com.hyoguoo.paymentplatform.core.common.metrics.annotation.PaymentStatusChange;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayPort;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
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
    @PublishPaymentHistory(action = "changed")
    @PaymentStatusChange(toStatus = "IN_PROGRESS", trigger = "confirm")
    public PaymentEvent executePayment(PaymentEvent paymentEvent, String paymentKey) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.execute(paymentKey, now, now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishPaymentHistory(action = "changed")
    @PaymentStatusChange(toStatus = "DONE", trigger = "auto")
    public PaymentEvent markPaymentAsDone(PaymentEvent paymentEvent, LocalDateTime approvedAt) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.done(approvedAt, now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishPaymentHistory(action = "changed")
    @PaymentStatusChange(toStatus = "FAILED", trigger = "auto")
    public PaymentEvent markPaymentAsFail(PaymentEvent paymentEvent, @Reason String failureReason) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.fail(failureReason, now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishPaymentHistory(action = "changed")
    @PaymentStatusChange(toStatus = "UNKNOWN", trigger = "auto")
    public PaymentEvent markPaymentAsUnknown(PaymentEvent paymentEvent, @Reason String reason) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.unknown(reason, now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishPaymentHistory(action = "retry")
    public PaymentEvent increaseRetryCount(PaymentEvent paymentEvent) {
        paymentEvent.increaseRetryCount();
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishPaymentHistory(action = "changed")
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
