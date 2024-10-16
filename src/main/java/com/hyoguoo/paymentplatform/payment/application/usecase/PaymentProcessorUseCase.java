package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmGatewayCommand;
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
public class PaymentProcessorUseCase {

    private final PaymentEventRepository paymentEventRepository;
    private final PaymentGatewayPort paymentGatewayPort;
    private final LocalDateTimeProvider localDateTimeProvider;

    public PaymentEvent executePayment(PaymentEvent paymentEvent, String paymentKey) {
        paymentEvent.execute(paymentKey, localDateTimeProvider.now());
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    public PaymentEvent markPaymentAsDone(PaymentEvent paymentEvent, LocalDateTime approvedAt) {
        paymentEvent.done(approvedAt);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    public PaymentEvent markPaymentAsFail(PaymentEvent paymentEvent) {
        paymentEvent.fail();
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    public PaymentEvent markPaymentAsUnknown(PaymentEvent paymentEvent) {
        paymentEvent.unknown();
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

    @Transactional
    public void increaseRetryCount(PaymentEvent paymentEvent) {
        paymentEvent.increaseRetryCount();
        paymentEventRepository.saveOrUpdate(paymentEvent);
    }
}
