package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.PaymentProcess;
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

    private final PaymentProcessUseCase paymentProcessUseCase;
    private final OrderedProductUseCase orderedProductUseCase;
    private final PaymentCommandUseCase paymentCommandUseCase;

    @Transactional(rollbackFor = PaymentOrderedProductStockException.class)
    public PaymentProcess executeStockDecreaseWithJobCreation(String orderId, List<PaymentOrder> paymentOrderList)
            throws PaymentOrderedProductStockException {
        orderedProductUseCase.decreaseStockForOrders(paymentOrderList);

        return paymentProcessUseCase.createProcessingJob(orderId);
    }

    @Transactional
    public PaymentEvent executePaymentSuccessCompletion(
            String orderId,
            PaymentEvent paymentEvent,
            LocalDateTime approvedAt
    ) {
        paymentProcessUseCase.completeJob(orderId);

        return paymentCommandUseCase.markPaymentAsDone(paymentEvent, approvedAt);
    }

    @Transactional
    public PaymentEvent executePaymentFailureCompensation(
            String orderId,
            PaymentEvent paymentEvent,
            List<PaymentOrder> paymentOrderList,
            String failureReason
    ) {
        if (paymentProcessUseCase.existsByOrderId(orderId)) {
            paymentProcessUseCase.failJob(orderId, failureReason);
        }

        orderedProductUseCase.increaseStockForOrders(paymentOrderList);

        return paymentCommandUseCase.markPaymentAsFail(paymentEvent, failureReason);
    }
}
