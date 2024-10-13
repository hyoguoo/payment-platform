package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.application.usecase.OrderedProductUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentProcessorUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossConfirmException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentConfirmService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentConfirmServiceImpl implements PaymentConfirmService {

    private final PaymentLoadUseCase paymentLoadUseCase;
    private final OrderedProductUseCase orderedProductUseCase;
    private final PaymentProcessorUseCase paymentProcessorUseCase;

    @Override
    public PaymentConfirmResult confirm(PaymentConfirmCommand paymentConfirmCommand) {
        PaymentEvent paymentEvent = paymentLoadUseCase.getPaymentEventByOrderId(
                paymentConfirmCommand.getOrderId()
        );
        paymentProcessorUseCase.executePayment(
                paymentEvent,
                paymentConfirmCommand.getPaymentKey()
        );

        try {
            orderedProductUseCase.decreaseStockForOrders(paymentEvent.getPaymentOrderList());
            PaymentEvent completedPayment = processPayment(paymentEvent, paymentConfirmCommand);

            return PaymentConfirmResult.builder()
                    .amount(completedPayment.getTotalAmount())
                    .orderId(completedPayment.getOrderId())
                    .build();
        } catch (PaymentOrderedProductStockException e) {
            handleStockFailure(paymentEvent);
            throw PaymentTossConfirmException.of(PaymentErrorCode.ORDERED_PRODUCT_STOCK_NOT_ENOUGH);
        } catch (PaymentTossRetryableException e) {
            handleRetryableFailure(paymentEvent);
            throw PaymentTossConfirmException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR);
        } catch (PaymentTossNonRetryableException e) {
            handleNonRetryableFailure(paymentEvent);
            throw PaymentTossConfirmException.of(PaymentErrorCode.TOSS_NON_RETRYABLE_ERROR);
        } catch (Exception e) {
            handleUnknownException(paymentEvent);
            throw e;
        }
    }

    private PaymentEvent processPayment(
            PaymentEvent paymentEvent, PaymentConfirmCommand paymentConfirmCommand
    ) throws PaymentTossRetryableException, PaymentTossNonRetryableException {
        paymentProcessorUseCase.validateCompletionStatus(paymentEvent, paymentConfirmCommand);

        TossPaymentInfo tossConfirmInfo = paymentProcessorUseCase.confirmPaymentWithGateway(
                paymentConfirmCommand
        );

        return paymentProcessorUseCase.markPaymentAsDone(
                paymentEvent,
                tossConfirmInfo.getPaymentDetails().getApprovedAt()
        );
    }

    private void handleStockFailure(PaymentEvent paymentEvent) {
        paymentProcessorUseCase.markPaymentAsFail(paymentEvent);
    }

    private void handleNonRetryableFailure(PaymentEvent paymentEvent) {
        PaymentEvent failedPaymentEvent = paymentProcessorUseCase.markPaymentAsFail(paymentEvent);
        orderedProductUseCase.increaseStockForOrders(failedPaymentEvent.getPaymentOrderList());
    }

    private void handleRetryableFailure(PaymentEvent paymentEvent) {
        paymentProcessorUseCase.markPaymentAsUnknown(paymentEvent);
    }

    private void handleUnknownException(PaymentEvent paymentEvent) {
        paymentProcessorUseCase.markPaymentAsFail(paymentEvent);
        orderedProductUseCase.increaseStockForOrders(paymentEvent.getPaymentOrderList());
    }
}
