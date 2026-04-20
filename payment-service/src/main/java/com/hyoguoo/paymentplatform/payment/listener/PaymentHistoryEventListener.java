package com.hyoguoo.paymentplatform.payment.listener;

import com.hyoguoo.paymentplatform.payment.domain.event.PaymentHistoryEvent;
import com.hyoguoo.paymentplatform.payment.listener.port.PaymentHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentHistoryEventListener {

    private final PaymentHistoryService paymentHistoryService;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void handlePaymentHistoryEvent(PaymentHistoryEvent event) {
        paymentHistoryService.recordPaymentHistory(event);
    }
}
