package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
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

    private final StockReductionUseCase stockReductionUseCase;
    private final PaymentEventUseCase paymentEventUseCase;

    @Override
    public PaymentConfirmResult confirm(PaymentConfirmCommand paymentConfirmCommand) {
        PaymentEvent paymentEvent = paymentEventUseCase.findAndExecutePayment(
                paymentConfirmCommand
        );

        stockReductionUseCase.reduceStock(paymentEvent.getPaymentOrderList());

        try {
            PaymentEvent completedPayment = processPayment(paymentEvent, paymentConfirmCommand);

            return PaymentConfirmResult.builder()
                    .amount(completedPayment.getTotalAmount())
                    .orderId(completedPayment.getOrderId())
                    .build();
        } catch (PaymentTossRetryableException e) {
            handleRetryableFailure(paymentEvent);
            throw PaymentTossConfirmException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR);
        } catch (PaymentTossNonRetryableException e) {
            handleNonRetryableFailure(paymentEvent);
            throw PaymentTossConfirmException.of(PaymentErrorCode.TOSS_NON_RETRYABLE_ERROR);
        } catch (RuntimeException e) {
            handleNonRetryableFailure(paymentEvent);
            throw e;
        }
    }

    private PaymentEvent processPayment(
            PaymentEvent paymentEvent, PaymentConfirmCommand paymentConfirmCommand
    ) throws PaymentTossRetryableException, PaymentTossNonRetryableException {
        paymentEventUseCase.validatePayment(paymentEvent, paymentConfirmCommand);

        TossPaymentInfo tossConfirmInfo = paymentEventUseCase.confirmPaymentWithGateway(
                paymentConfirmCommand
        );

        return paymentEventUseCase.markPaymentAsDone(paymentEvent,
                tossConfirmInfo.getPaymentDetails().getApprovedAt()
        );
    }

    private void handleNonRetryableFailure(PaymentEvent paymentEvent) {
        PaymentEvent failedPaymentEvent = paymentEventUseCase.markPaymentAsFail(paymentEvent);
        stockReductionUseCase.increaseStockPaymentOrderListProduct(
                failedPaymentEvent.getPaymentOrderList()
        );
    }

    private void handleRetryableFailure(PaymentEvent paymentEvent) {
        paymentEventUseCase.markPaymentAsUnknown(paymentEvent);
    }
}
