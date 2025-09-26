package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentProcessorUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.scheduler.port.PaymentExpirationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentExpirationServiceImpl implements PaymentExpirationService {

    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentProcessorUseCase paymentProcessorUseCase;

    @Override
    @Transactional
    public void expireOldReadyPayments() {
        List<PaymentEvent> expiredPayments = paymentLoadUseCase.getReadyPaymentsOlder();
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_EXPIRATION_START, () ->
                String.format("Expiring %d old READY payments", expiredPayments.size()));

        expiredPayments.forEach(this::expirePaymentEvent);
    }

    private void expirePaymentEvent(PaymentEvent paymentEvent) {
        PaymentEvent expiredPaymentEvent = paymentProcessorUseCase.expirePayment(paymentEvent);
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_EXPIRED, () ->
                String.format("Payment expired - orderId=%s, paymentEventId=%d",
                        expiredPaymentEvent.getOrderId(), expiredPaymentEvent.getId()));
    }
}
