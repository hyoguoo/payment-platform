package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentLoadUseCase {

    public static final int RETRYABLE_MINUTES_FOR_IN_PROGRESS = 5;
    public static final int RETRYABLE_LIMIT = 5;
    private final PaymentEventRepository paymentEventRepository;
    private final LocalDateTimeProvider localDateTimeProvider;

    public PaymentEvent getPaymentEventByOrderId(String orderId) {
        return paymentEventRepository
                .findByOrderId(orderId)
                .orElseThrow(
                        () -> PaymentFoundException.of(PaymentErrorCode.PAYMENT_EVENT_NOT_FOUND)
                );
    }

    public List<PaymentEvent> getRetryablePaymentEvents() {
        LocalDateTime before = localDateTimeProvider.now().minusMinutes(RETRYABLE_MINUTES_FOR_IN_PROGRESS);
        return paymentEventRepository.findDelayedInProgressOrUnknownEvents(before, RETRYABLE_LIMIT);
    }
}
