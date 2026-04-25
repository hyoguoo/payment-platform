package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentHistoryUseCase;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentHistoryEvent;
import com.hyoguoo.paymentplatform.payment.application.port.in.PaymentHistoryService;
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
        } catch (RuntimeException e) {
            // T-F2: savePaymentHistory 는 unchecked exception 만 throw — RuntimeException 으로 축소
            LogFmt.error(log, LogDomain.PAYMENT, EventType.PAYMENT_HISTORY_SAVE_FAIL,
                    () -> "orderId=" + event.getOrderId() + " error=" + e.getMessage());
            throw e;
        }
    }
}
