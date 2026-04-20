package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentHistoryUseCase;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentHistoryEvent;
import com.hyoguoo.paymentplatform.payment.listener.port.PaymentHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentHistoryServiceImpl implements PaymentHistoryService {

    private final PaymentHistoryUseCase paymentHistoryUseCase;

    @Override
    public void recordPaymentHistory(PaymentHistoryEvent event) {
        log.debug("Processing payment history event: type={}, paymentEventId={}, orderId={}",
                event.getEventType(), event.getPaymentEventId(), event.getOrderId());

        try {
            paymentHistoryUseCase.savePaymentHistory(event);
            log.debug("Successfully saved payment history for orderId: {}", event.getOrderId());
        } catch (Exception e) {
            log.error("Failed to save payment history for event: {}", event, e);
            throw e;
        }
    }
}
