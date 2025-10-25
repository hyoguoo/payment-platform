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
public class PaymentRecoveryUseCase {

    private final PaymentFailureUseCase paymentFailureUseCase;

    public PaymentEvent markRecoverySuccess(
            PaymentEvent originalEvent,
            PaymentEvent recoveredEvent
    ) {
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVER_SUCCESS,
                () -> String.format("orderId=%s fromStatus=%s toStatus=%s",
                        recoveredEvent.getOrderId(),
                        originalEvent.getStatus(),
                        recoveredEvent.getStatus()));
        return recoveredEvent;
    }

    public PaymentEvent markRecoveryFailure(
            PaymentEvent paymentEvent,
            String reason,
            String failureMessage
    ) {
        LogFmt.error(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVER_FAIL,
                () -> String.format("orderId=%s reason=%s failureMessage=%s",
                        paymentEvent.getOrderId(),
                        reason,
                        failureMessage));
        return paymentFailureUseCase.handleNonRetryableFailure(paymentEvent, failureMessage);
    }

    public PaymentEvent markRecoveryRetryableFailure(
            PaymentEvent paymentEvent,
            String reason,
            String failureMessage
    ) {
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVER_FAIL,
                () -> String.format("orderId=%s reason=%s failureMessage=%s (will retry in next schedule)",
                        paymentEvent.getOrderId(),
                        reason,
                        failureMessage));
        return paymentFailureUseCase.handleRetryableFailure(paymentEvent, failureMessage);
    }
}
