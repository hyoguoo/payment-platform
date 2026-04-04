package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOrderedProductStockException;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionCoordinator {

    private final OrderedProductUseCase orderedProductUseCase;
    private final PaymentCommandUseCase paymentCommandUseCase;
    private final PaymentOutboxUseCase paymentOutboxUseCase;

    @Transactional(rollbackFor = PaymentOrderedProductStockException.class)
    public PaymentEvent executePaymentAndStockDecreaseWithOutbox(
            PaymentEvent paymentEvent,
            String paymentKey,
            String orderId,
            List<PaymentOrder> paymentOrderList
    ) throws PaymentOrderedProductStockException {
        PaymentEvent inProgressEvent = paymentCommandUseCase.executePayment(paymentEvent, paymentKey);
        orderedProductUseCase.decreaseStockForOrders(paymentOrderList);
        paymentOutboxUseCase.createPendingRecord(orderId);
        return inProgressEvent;
    }

    @Transactional
    public PaymentEvent executePaymentSuccessCompletionWithOutbox(
            PaymentEvent paymentEvent,
            LocalDateTime approvedAt,
            PaymentOutbox outbox
    ) {
        outbox.toDone();
        paymentOutboxUseCase.save(outbox);
        return paymentCommandUseCase.markPaymentAsDone(paymentEvent, approvedAt);
    }

    @Transactional
    public PaymentEvent executePaymentFailureCompensationWithOutbox(
            PaymentEvent paymentEvent,
            List<PaymentOrder> paymentOrderList,
            String failureReason,
            PaymentOutbox outbox
    ) {
        outbox.toFailed();
        paymentOutboxUseCase.save(outbox);
        orderedProductUseCase.increaseStockForOrders(paymentOrderList);
        return paymentCommandUseCase.markPaymentAsFail(paymentEvent, failureReason);
    }

}
