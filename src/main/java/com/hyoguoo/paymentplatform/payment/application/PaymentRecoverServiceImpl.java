package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandrUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentRecoveryUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.exception.PaymentRetryableValidateException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.scheduler.port.PaymentRecoverService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRecoverServiceImpl implements PaymentRecoverService {

    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentCommandrUseCase paymentCommandrUseCase;
    private final PaymentRecoveryUseCase paymentRecoveryUseCase;
    private final LocalDateTimeProvider localDateTimeProvider;

    @Override
    public void recoverRetryablePayment() {
        List<PaymentEvent> retryablePaymentEvents = paymentLoadUseCase.getRetryablePaymentEvents();
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVER_RETRYABLE_START, () ->
                String.format("Retry Event Count=%s", retryablePaymentEvents.size()));
        retryablePaymentEvents.forEach(this::processRetryablePaymentEvent);
    }

    @Override
    public void recoverStuckPayments() {
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_START,
                () -> "Starting stuck payment recovery process");
        paymentRecoveryUseCase.recoverStuckPayments();
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_END,
                () -> "Finished stuck payment recovery process");
    }

    private void processRetryablePaymentEvent(PaymentEvent retryablePaymentEvent) {
        try {
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RETRY_START,
                    () -> String.format("orderId=%s retryCount=%s, status=%s",
                            retryablePaymentEvent.getOrderId(),
                            retryablePaymentEvent.getRetryCount(),
                            retryablePaymentEvent.getStatus()));
            if (!retryablePaymentEvent.isRetryable(localDateTimeProvider.now())) {
                throw PaymentRetryableValidateException.of(PaymentErrorCode.RETRYABLE_VALIDATION_ERROR);
            }

            PaymentEvent increasedRetryCountEvent = paymentCommandrUseCase.increaseRetryCount(retryablePaymentEvent);

            PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                    .userId(increasedRetryCountEvent.getBuyerId())
                    .orderId(increasedRetryCountEvent.getOrderId())
                    .amount(increasedRetryCountEvent.getTotalAmount())
                    .paymentKey(increasedRetryCountEvent.getPaymentKey())
                    .build();

            TossPaymentInfo tossPaymentInfo = paymentCommandrUseCase.confirmPaymentWithGateway(
                    paymentConfirmCommand
            );

            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_SUCCESS_WITH_RETRY,
                    () -> String.format("orderId=%s approvedAt=%s",
                            increasedRetryCountEvent.getOrderId(),
                            tossPaymentInfo.getPaymentDetails().getApprovedAt()));

            PaymentEvent donePaymentEvent = paymentCommandrUseCase.markPaymentAsDone(
                    increasedRetryCountEvent,
                    tossPaymentInfo.getPaymentDetails().getApprovedAt()
            );

            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_STATUS_TO_DONE,
                    () -> String.format("orderId=%s approvedAt=%s",
                            donePaymentEvent.getOrderId(),
                            donePaymentEvent.getApprovedAt()));

            paymentRecoveryUseCase.markRecoverySuccess(retryablePaymentEvent, donePaymentEvent);
        } catch (PaymentRetryableValidateException | PaymentTossNonRetryableException e) {
            paymentRecoveryUseCase.markRecoveryFailure(retryablePaymentEvent, "NON_RETRYABLE_ERROR", e.getMessage());
        } catch (PaymentTossRetryableException e) {
            paymentRecoveryUseCase.markRecoveryRetryableFailure(retryablePaymentEvent, "RETRYABLE_ERROR",
                    e.getMessage());
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : "Unknown error occurred during recovery";
            paymentRecoveryUseCase.markRecoveryFailure(retryablePaymentEvent, "UNKNOWN_ERROR", message);
        }
    }
}
