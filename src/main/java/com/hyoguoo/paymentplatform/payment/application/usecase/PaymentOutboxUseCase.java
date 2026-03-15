package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.port.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentOutboxUseCase {

    private final PaymentOutboxRepository paymentOutboxRepository;

    @Transactional
    public PaymentOutbox createPendingRecord(String orderId) {
        throw new UnsupportedOperationException("Plan 02에서 구현");
    }
}
