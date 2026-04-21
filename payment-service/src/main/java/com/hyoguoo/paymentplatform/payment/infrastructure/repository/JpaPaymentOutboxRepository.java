package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOutboxEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaPaymentOutboxRepository extends JpaRepository<PaymentOutboxEntity, Long> {

    Optional<PaymentOutboxEntity> findByOrderId(String orderId);

    @Query("SELECT e FROM PaymentOutboxEntity e WHERE e.status = 'PENDING' AND (e.nextRetryAt IS NULL OR e.nextRetryAt <= :now) ORDER BY e.createdAt ASC")
    List<PaymentOutboxEntity> findPendingBatch(@Param("now") LocalDateTime now, Pageable pageable);

    @Query("SELECT e FROM PaymentOutboxEntity e WHERE e.status = 'IN_FLIGHT' AND e.inFlightAt < :before")
    List<PaymentOutboxEntity> findTimedOutInFlight(@Param("before") LocalDateTime before);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE PaymentOutboxEntity e SET e.status = :toStatus, e.inFlightAt = :inFlightAt WHERE e.orderId = :orderId AND e.status = :fromStatus AND (e.nextRetryAt IS NULL OR e.nextRetryAt <= :now)")
    int claimToInFlight(@Param("orderId") String orderId,
                        @Param("inFlightAt") LocalDateTime inFlightAt,
                        @Param("toStatus") PaymentOutboxStatus toStatus,
                        @Param("fromStatus") PaymentOutboxStatus fromStatus,
                        @Param("now") LocalDateTime now);

    // ── 관측 지표 집계 (T2d-02, ADR-31) ─────────────────────────────────────────

    @Query("SELECT COUNT(e) FROM PaymentOutboxEntity e WHERE e.status = 'PENDING'")
    long countPending();

    @Query("SELECT COUNT(e) FROM PaymentOutboxEntity e WHERE e.status = 'PENDING' AND e.nextRetryAt IS NOT NULL AND e.nextRetryAt > :now")
    long countFuturePending(@Param("now") LocalDateTime now);

    @Query("SELECT MIN(e.createdAt) FROM PaymentOutboxEntity e WHERE e.status = 'PENDING'")
    Optional<LocalDateTime> findOldestPendingCreatedAt();
}
