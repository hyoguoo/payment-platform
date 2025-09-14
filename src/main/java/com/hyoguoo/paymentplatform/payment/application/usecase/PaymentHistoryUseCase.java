package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.port.PaymentHistoryRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentHistory;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentHistoryEvent;
import com.hyoguoo.paymentplatform.payment.domain.factory.PaymentHistoryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentHistoryUseCase {

    private final PaymentHistoryRepository paymentHistoryRepository;
    private final PaymentHistoryFactory paymentHistoryFactory;

    @Transactional
    public void savePaymentHistory(PaymentHistoryEvent event) {
        PaymentHistory paymentHistory = paymentHistoryFactory.createFromEvent(event);
        paymentHistoryRepository.save(paymentHistory);
    }
}
