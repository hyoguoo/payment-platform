package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmGatewayCommand;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayHandler;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentProcessorUseCase {

    private final PaymentEventRepository paymentEventRepository;
    private final PaymentGatewayHandler paymentGatewayHandler;

    public PaymentEvent findAndExecutePayment(PaymentConfirmCommand paymentConfirmCommand) {
        PaymentEvent paymentEvent = paymentEventRepository
                .findByOrderId(paymentConfirmCommand.getOrderId())
                .orElseThrow(
                        () -> PaymentFoundException.of(PaymentErrorCode.PAYMENT_EVENT_NOT_FOUND)
                );

        return executePayment(paymentEvent, paymentConfirmCommand.getPaymentKey());
    }

    private PaymentEvent executePayment(PaymentEvent paymentEvent, String paymentKey) {
        paymentEvent.execute(paymentKey);
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

    public void validatePayment(
            PaymentEvent paymentEvent,
            PaymentConfirmCommand paymentConfirmCommand
    ) {
        TossPaymentInfo tossPaymentInfo = paymentGatewayHandler.getPaymentInfoByOrderId(
                paymentConfirmCommand.getOrderId()
        );

        paymentEvent.validate(paymentConfirmCommand, tossPaymentInfo);
    }

    public TossPaymentInfo confirmPaymentWithGateway(PaymentConfirmCommand paymentConfirmCommand)
            throws PaymentTossRetryableException, PaymentTossNonRetryableException {
        TossConfirmGatewayCommand tossConfirmGatewayCommand = TossConfirmGatewayCommand.builder()
                .orderId(paymentConfirmCommand.getOrderId())
                .paymentKey(paymentConfirmCommand.getPaymentKey())
                .amount(paymentConfirmCommand.getAmount())
                .idempotencyKey(paymentConfirmCommand.getOrderId())
                .build();

        TossPaymentInfo tossPaymentInfo = paymentGatewayHandler.confirmPayment(
                tossConfirmGatewayCommand
        );

        PaymentConfirmResultStatus paymentConfirmResultStatus = tossPaymentInfo.getPaymentConfirmResultStatus();

        return switch (paymentConfirmResultStatus) {
            case PaymentConfirmResultStatus.SUCCESS -> tossPaymentInfo;
            case PaymentConfirmResultStatus.RETRYABLE_FAILURE ->
                    throw PaymentTossRetryableException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR);
            case PaymentConfirmResultStatus.NON_RETRYABLE_FAILURE ->
                    throw PaymentTossNonRetryableException.of(
                            PaymentErrorCode.TOSS_NON_RETRYABLE_ERROR
                    );
        };
    }
}
