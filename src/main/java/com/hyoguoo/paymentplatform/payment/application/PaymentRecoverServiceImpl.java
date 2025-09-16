package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.usecase.OrderedProductUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentProcessorUseCase;
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
    private final OrderedProductUseCase orderedProductUseCase;
    private final PaymentProcessorUseCase paymentProcessorUseCase;
    private final LocalDateTimeProvider localDateTimeProvider;

    @Override
    public void recoverRetryablePayment() {
        List<PaymentEvent> retryablePaymentEvents = paymentLoadUseCase.getRetryablePaymentEvents();
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVER_RETRYABLE_START, () ->
                String.format("Retry Event Count=%s", retryablePaymentEvents.size()));
        retryablePaymentEvents.forEach(this::processRetryablePaymentEvent);
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

            PaymentEvent increasedRetryCountEvent = paymentProcessorUseCase.increaseRetryCount(retryablePaymentEvent);

            PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                    .userId(increasedRetryCountEvent.getBuyerId())
                    .orderId(increasedRetryCountEvent.getOrderId())
                    .amount(increasedRetryCountEvent.getTotalAmount())
                    .paymentKey(increasedRetryCountEvent.getPaymentKey())
                    .build();
            TossPaymentInfo tossPaymentInfo = paymentProcessorUseCase.confirmPaymentWithGateway(
                    paymentConfirmCommand
            );
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_SUCCESS_WITH_RETRY,
                    () -> String.format("orderId=%s approvedAt=%s",
                            increasedRetryCountEvent.getOrderId(),
                            tossPaymentInfo.getPaymentDetails().getApprovedAt()));
            paymentProcessorUseCase.markPaymentAsDone(
                    increasedRetryCountEvent,
                    tossPaymentInfo.getPaymentDetails().getApprovedAt()
            );
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_STATUS_TO_DONE,
                    () -> String.format("orderId=%s approvedAt=%s",
                            increasedRetryCountEvent.getOrderId(),
                            tossPaymentInfo.getPaymentDetails().getApprovedAt()));
        } catch (PaymentRetryableValidateException
                 | PaymentTossNonRetryableException e) {
            handleNonRetryableFailure(retryablePaymentEvent, e.getMessage());
        } catch (PaymentTossRetryableException e) {
            handleRetryableFailure(retryablePaymentEvent, e.getMessage());
        } catch (Exception e) {
            handleUnknownFailure(retryablePaymentEvent, e.getMessage());
        }
    }

    private void handleRetryableFailure(PaymentEvent paymentEvent, String failureMessage) {
        paymentProcessorUseCase.markPaymentAsUnknown(paymentEvent, failureMessage);
        LogFmt.warn(log, LogDomain.PAYMENT, EventType.PAYMENT_STATUS_TO_UNKNOWN,
                () -> String.format("orderId=%s reason=%s", paymentEvent.getOrderId(), failureMessage));
    }

    private void handleNonRetryableFailure(PaymentEvent paymentEvent, String failureMessage) {
        PaymentEvent failedPaymentEvent = paymentProcessorUseCase.markPaymentAsFail(paymentEvent, failureMessage);
        LogFmt.error(log, LogDomain.PAYMENT, EventType.PAYMENT_STATUS_TO_FAIL,
                () -> String.format("orderId=%s reason=%s", paymentEvent.getOrderId(), failureMessage));
        orderedProductUseCase.increaseStockForOrders(failedPaymentEvent.getPaymentOrderList());
        LogFmt.info(log, LogDomain.PAYMENT, EventType.STOCK_INCREASE_REQUEST,
                () -> String.format("products=%s by orderId=%s",
                        LogFmt.toJson(failedPaymentEvent.getPaymentOrderList()),
                        failedPaymentEvent.getOrderId()));
    }

    private void handleUnknownFailure(PaymentEvent paymentEvent, String failureMessage) {
        String message = failureMessage != null ? failureMessage : "Unknown error occurred during recovery";

        PaymentEvent failedPaymentEvent = paymentProcessorUseCase.markPaymentAsFail(paymentEvent, message);
        LogFmt.error(log, LogDomain.PAYMENT, EventType.PAYMENT_STATUS_TO_FAIL,
                () -> String.format("orderId=%s reason=%s", paymentEvent.getOrderId(), message));
        orderedProductUseCase.increaseStockForOrders(failedPaymentEvent.getPaymentOrderList());
        LogFmt.error(log, LogDomain.PAYMENT, EventType.STOCK_INCREASE_REQUEST,
                () -> String.format("products=%s by orderId=%s",
                        LogFmt.toJson(failedPaymentEvent.getPaymentOrderList()),
                        failedPaymentEvent.getOrderId()));
    }
}
