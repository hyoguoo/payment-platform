package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentEventRepository {

    Optional<PaymentEvent> findById(Long id);

    Optional<PaymentEvent> findByOrderId(String orderId);

    PaymentEvent saveOrUpdate(PaymentEvent paymentEvent);

    List<PaymentEvent> findDelayedInProgressOrUnknownEvents(LocalDateTime before);
}
