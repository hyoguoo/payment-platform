package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayPort;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentProcess;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRecoveryUseCase {

    private final PaymentFailureUseCase paymentFailureUseCase;
    private final PaymentProcessUseCase paymentProcessUseCase;
    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentTransactionCoordinator transactionCoordinator;
    private final PaymentGatewayPort paymentGatewayPort;

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

    public void recoverStuckPayments() {
        List<PaymentProcess> processingJobs = paymentProcessUseCase.findAllProcessingJobs();

        if (processingJobs.isEmpty()) {
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_NO_JOBS,
                    () -> "No stuck payments found");
            return;
        }

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_JOBS_FOUND,
                () -> String.format("Found %d stuck payments", processingJobs.size()));

        processingJobs.forEach(job -> {
            try {
                recoverSinglePayment(job.getOrderId());
            } catch (Exception e) {
                LogFmt.error(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVER_FAIL,
                        () -> String.format("Failed to recover orderId=%s error=%s",
                                job.getOrderId(), e.getMessage()));
            }
        });
    }

    private void recoverSinglePayment(String orderId) {
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SINGLE_START,
                () -> String.format("Starting recovery for orderId=%s", orderId));

        PaymentStatusResult statusResult = paymentGatewayPort.getStatusByOrderId(orderId);
        PaymentEvent paymentEvent = paymentLoadUseCase.getPaymentEventByOrderId(orderId);

        PaymentStatus paymentStatus = statusResult.status();

        if (paymentStatus == PaymentStatus.DONE) {
            transactionCoordinator.executePaymentSuccessCompletion(
                    orderId,
                    paymentEvent,
                    statusResult.approvedAt()
            );
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SUCCESS_COMPLETION,
                    () -> String.format("Recovered orderId=%s as SUCCESS", orderId));
        } else {
            transactionCoordinator.executePaymentFailureCompensation(
                    orderId,
                    paymentEvent,
                    paymentEvent.getPaymentOrderList(),
                    paymentStatus.getValue()
            );
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_FAILURE_COMPENSATION,
                    () -> String.format("Recovered orderId=%s as FAILURE (status=%s)",
                            orderId, paymentStatus.getValue()));
        }
    }
}
