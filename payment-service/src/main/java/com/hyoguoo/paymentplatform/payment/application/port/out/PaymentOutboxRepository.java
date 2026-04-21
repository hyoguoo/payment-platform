package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentOutboxRepository {

    PaymentOutbox save(PaymentOutbox paymentOutbox);

    Optional<PaymentOutbox> findByOrderId(String orderId);

    List<PaymentOutbox> findPendingBatch(int limit);

    List<PaymentOutbox> findTimedOutInFlight(LocalDateTime before);

    boolean claimToInFlight(String orderId, LocalDateTime inFlightAt);

    // ── 관측 지표 집계 (T2d-02, ADR-31) ────────────────────────────────────────

    /**
     * PENDING 상태 row 수를 반환한다.
     */
    long countPending();

    /**
     * PENDING 이면서 nextRetryAt &gt; now 인 row 수를 반환한다 (미래 예약 재시도).
     */
    long countFuturePending(LocalDateTime now);

    /**
     * PENDING row 중 가장 오래된 createdAt을 반환한다.
     * PENDING row가 없으면 Optional.empty().
     */
    Optional<LocalDateTime> findOldestPendingCreatedAt();
}
