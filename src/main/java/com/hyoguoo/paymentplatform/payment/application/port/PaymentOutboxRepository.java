package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentOutboxRepository {

    PaymentOutbox save(PaymentOutbox paymentOutbox);

    Optional<PaymentOutbox> findByOrderId(String orderId);

    List<PaymentOutbox> findPendingBatch(int limit);

    List<PaymentOutbox> findTimedOutInFlight(LocalDateTime before);

}
