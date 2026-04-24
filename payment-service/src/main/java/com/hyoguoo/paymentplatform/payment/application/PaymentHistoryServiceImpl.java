package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
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
        LogFmt.debug(log, LogDomain.PAYMENT, EventType.PAYMENT_HISTORY_SAVE_START,
                () -> "type=" + event.getEventType()
                        + " paymentEventId=" + event.getPaymentEventId()
                        + " orderId=" + event.getOrderId());

        try {
            paymentHistoryUseCase.savePaymentHistory(event);
            LogFmt.debug(log, LogDomain.PAYMENT, EventType.PAYMENT_HISTORY_SAVE_DONE,
                    () -> "orderId=" + event.getOrderId());
        } catch (Exception e) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.PAYMENT_HISTORY_SAVE_FAIL,
                    () -> "orderId=" + event.getOrderId() + " error=" + e.getMessage());
            throw e;
        }
    }
}
