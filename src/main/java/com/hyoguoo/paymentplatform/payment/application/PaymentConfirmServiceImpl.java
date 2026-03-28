package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult.ResponseType;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentFailureUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentGatewayInfo;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossConfirmException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentConfirmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "spring.payment.async-strategy",
        havingValue = "sync",
        matchIfMissing = false
)
public class PaymentConfirmServiceImpl implements PaymentConfirmService {

    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentTransactionCoordinator transactionCoordinator;
    private final PaymentCommandUseCase paymentCommandUseCase;
    private final PaymentFailureUseCase paymentFailureUseCase;

    @Override
    public PaymentConfirmAsyncResult confirm(PaymentConfirmCommand paymentConfirmCommand)
            throws PaymentOrderedProductStockException {
        PaymentConfirmResult result = doConfirm(paymentConfirmCommand);

        return PaymentConfirmAsyncResult.builder()
                .responseType(ResponseType.SYNC_200)
                .orderId(result.getOrderId())
                .amount(result.getAmount())
                .build();
    }

    private PaymentConfirmResult doConfirm(PaymentConfirmCommand paymentConfirmCommand) {
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_START,
                () -> String.format("orderId=%s paymentKey=%s",
                        paymentConfirmCommand.getOrderId(),
                        paymentConfirmCommand.getPaymentKey()));

        PaymentEvent paymentEvent = paymentLoadUseCase.getPaymentEventByOrderId(
                paymentConfirmCommand.getOrderId()
        );

        validateLocalPaymentRequest(paymentEvent, paymentConfirmCommand);

        try {
            transactionCoordinator.executeStockDecreaseWithJobCreation(
                    paymentEvent.getOrderId(),
                    paymentEvent.getPaymentOrderList()
            );
            LogFmt.info(log, LogDomain.PAYMENT, EventType.STOCK_DECREASE_SUCESS,
                    () -> String.format("orderId=%s", paymentEvent.getOrderId()));
        } catch (PaymentOrderedProductStockException e) {
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.STOCK_DECREASE_FAIL,
                    () -> String.format("orderId=%s", paymentEvent.getOrderId()));
            paymentFailureUseCase.handleStockFailure(paymentEvent, e.getMessage());
            throw PaymentTossConfirmException.of(PaymentErrorCode.ORDERED_PRODUCT_STOCK_NOT_ENOUGH);
        }

        try {
            PaymentEvent inProgressPaymentEvent = paymentCommandUseCase.executePayment(
                    paymentEvent,
                    paymentConfirmCommand.getPaymentKey()
            );
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_STATUS_TO_IN_PROGRESS,
                    () -> String.format("orderId=%s", inProgressPaymentEvent.getOrderId()));

            PaymentEvent completedPayment = processPayment(inProgressPaymentEvent, paymentConfirmCommand);
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_SUCCESS,
                    () -> String.format("orderId=%s totalAmount=%s approvedAt=%s",
                            completedPayment.getOrderId(),
                            completedPayment.getTotalAmount(),
                            completedPayment.getApprovedAt()));

            return PaymentConfirmResult.builder()
                    .amount(completedPayment.getTotalAmount())
                    .orderId(completedPayment.getOrderId())
                    .build();
        } catch (PaymentStatusException e) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.PAYMENT_STATUS_ERROR,
                    () -> String.format("orderId=%s error=%s", paymentEvent.getOrderId(), e.getMessage()));
            paymentFailureUseCase.handleNonRetryableFailure(paymentEvent, e.getMessage());
            throw e;
        } catch (PaymentTossRetryableException e) {
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.PAYMENT_TOSS_RETRYABLE_ERROR,
                    () -> String.format("orderId=%s error=%s", paymentEvent.getOrderId(), e.getMessage()));
            paymentFailureUseCase.handleRetryableFailure(paymentEvent, e.getMessage());
            throw PaymentTossConfirmException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR);
        } catch (PaymentTossNonRetryableException e) {
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_TOSS_NON_RETRYABLE_ERROR,
                    () -> String.format("orderId=%s error=%s", paymentEvent.getOrderId(), e.getMessage()));
            paymentFailureUseCase.handleNonRetryableFailure(paymentEvent, e.getMessage());
            throw PaymentTossConfirmException.of(PaymentErrorCode.TOSS_NON_RETRYABLE_ERROR);
        } catch (Exception e) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_UNKNOWN_ERROR,
                    () -> String.format("orderId=%s error=%s", paymentEvent.getOrderId(), e.getMessage()));
            paymentFailureUseCase.handleUnknownFailure(paymentEvent, e.getMessage());
            throw e;
        }
    }

    private PaymentEvent processPayment(
            PaymentEvent paymentEvent, PaymentConfirmCommand paymentConfirmCommand
    ) throws PaymentTossRetryableException, PaymentTossNonRetryableException {
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_REQUEST_START,
                () -> String.format("orderId=%s", paymentConfirmCommand.getOrderId()));

        PaymentGatewayInfo paymentGatewayInfo = paymentCommandUseCase.confirmPaymentWithGateway(
                paymentConfirmCommand
        );

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_REQUEST_END,
                () -> String.format("orderId=%s status=%s",
                        paymentGatewayInfo.getOrderId(),
                        paymentGatewayInfo.getPaymentConfirmResultStatus()));

        PaymentEvent donePaymentEvent = transactionCoordinator.executePaymentSuccessCompletion(
                paymentEvent.getOrderId(),
                paymentEvent,
                paymentGatewayInfo.getPaymentDetails().getApprovedAt()
        );

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_STATUS_TO_DONE,
                () -> String.format("orderId=%s approvedAt=%s",
                        donePaymentEvent.getOrderId(),
                        donePaymentEvent.getApprovedAt()));

        return donePaymentEvent;
    }

    private void validateLocalPaymentRequest(PaymentEvent paymentEvent, PaymentConfirmCommand command) {
        if (!paymentEvent.getBuyerId().equals(command.getUserId())) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_USER_ID);
        }
        if (command.getAmount().compareTo(paymentEvent.getTotalAmount()) != 0) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_TOTAL_AMOUNT);
        }
        if (!paymentEvent.getOrderId().equals(command.getOrderId())) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_ORDER_ID);
        }
        if (!paymentEvent.getPaymentKey().equals(command.getPaymentKey())) {
            throw PaymentValidException.of(PaymentErrorCode.INVALID_PAYMENT_KEY);
        }
    }
}
