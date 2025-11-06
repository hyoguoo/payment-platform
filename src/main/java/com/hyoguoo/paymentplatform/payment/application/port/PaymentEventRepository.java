package com.hyoguoo.paymentplatform.payment.application.port;

import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PaymentEventRepository {

    Optional<PaymentEvent> findById(Long id);

    Optional<PaymentEvent> findByOrderId(String orderId);

    PaymentEvent saveOrUpdate(PaymentEvent paymentEvent);

    List<PaymentEvent> findDelayedInProgressOrUnknownEvents(LocalDateTime before);

    List<PaymentEvent> findReadyPaymentsOlderThan(LocalDateTime before);

    Map<PaymentEventStatus, Long> countByStatus();

    long countByStatusAndExecutedAtBefore(PaymentEventStatus status, LocalDateTime before);

    long countByRetryCountGreaterThanEqual(int retryCount);

    Map<PaymentEventStatus, Map<String, Long>> countByStatusAndAgeBuckets(
            LocalDateTime fiveMinutesAgo,
            LocalDateTime thirtyMinutesAgo
    );

    long countNearExpiration(LocalDateTime expirationThreshold);
}
