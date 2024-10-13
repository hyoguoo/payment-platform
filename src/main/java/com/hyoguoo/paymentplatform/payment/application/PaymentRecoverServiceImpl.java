package com.hyoguoo.paymentplatform.payment.application;

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
import org.springframework.stereotype.Service;

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
        retryablePaymentEvents.forEach(this::processRetryablePaymentEvent);
    }

    private void processRetryablePaymentEvent(PaymentEvent retryablePaymentEvent) {
        try {
            if (!retryablePaymentEvent.isRetryable(localDateTimeProvider.now())) {
                throw PaymentRetryableValidateException.of(PaymentErrorCode.RETRYABLE_VALIDATION_ERROR);
            }
            paymentProcessorUseCase.increaseRetryCount(retryablePaymentEvent);
            PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                    .userId(retryablePaymentEvent.getBuyerId())
                    .orderId(retryablePaymentEvent.getOrderId())
                    .amount(retryablePaymentEvent.getTotalAmount())
                    .paymentKey(retryablePaymentEvent.getPaymentKey())
                    .build();
            TossPaymentInfo tossPaymentInfo = paymentProcessorUseCase.confirmPaymentWithGateway(
                    paymentConfirmCommand
            );
            paymentProcessorUseCase.markPaymentAsDone(
                    retryablePaymentEvent,
                    tossPaymentInfo.getPaymentDetails().getApprovedAt()
            );
        } catch (PaymentRetryableValidateException
                 | PaymentTossNonRetryableException e) {
            handleNonRetryableFailure(retryablePaymentEvent);
        } catch (PaymentTossRetryableException e) {
            handleRetryableFailure(retryablePaymentEvent);
        }
    }

    private void handleRetryableFailure(PaymentEvent paymentEvent) {
        paymentProcessorUseCase.markPaymentAsUnknown(paymentEvent);
    }

    private void handleNonRetryableFailure(PaymentEvent paymentEvent) {
        PaymentEvent failedPaymentEvent = paymentProcessorUseCase.markPaymentAsFail(paymentEvent);
        orderedProductUseCase.increaseStockForOrders(failedPaymentEvent.getPaymentOrderList());
    }
}
