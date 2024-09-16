package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.infrastructure.repostitory.PaymentOrderRepository;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentFindService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentFindServiceImpl implements PaymentFindService {

    private final PaymentEventRepository paymentEventRepository;
    private final PaymentOrderRepository paymentOrderRepository;

    @Override
    public PaymentEvent getPaymentEventByOrderId(String orderId) {
        return paymentEventRepository
                .findByOrderId(orderId)
                .orElseThrow(
                        () -> PaymentFoundException.of(PaymentErrorCode.PAYMENT_EVENT_NOT_FOUND)
                );
    }
}
