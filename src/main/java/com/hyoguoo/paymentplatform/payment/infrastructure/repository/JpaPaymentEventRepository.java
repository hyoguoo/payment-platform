package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaPaymentEventRepository extends JpaRepository<PaymentEventEntity, Long> {

    Optional<PaymentEventEntity> findByOrderId(String orderId);

    @Query("SELECT pe FROM PaymentEventEntity pe WHERE ((pe.status = 'IN_PROGRESS' AND pe.executedAt < :before) OR pe.status = 'UNKNOWN')")
    List<PaymentEventEntity> findByInProgressWithTimeConstraintOrUnknown(@Param("before") LocalDateTime before);

    @Query("SELECT pe FROM PaymentEventEntity pe WHERE pe.status = 'READY' AND pe.createdAt < :before")
    List<PaymentEventEntity> findReadyPaymentsOlderThan(@Param("before") LocalDateTime before);

    @Query("SELECT pe.status, COUNT(pe) FROM PaymentEventEntity pe GROUP BY pe.status")
    List<Object[]> countByStatusGrouped();

    @Query("SELECT COUNT(pe) FROM PaymentEventEntity pe WHERE pe.status = :status AND pe.executedAt < :before")
    long countByStatusAndExecutedAtBefore(@Param("status") PaymentEventStatus status,
            @Param("before") LocalDateTime before);

    long countByRetryCountGreaterThanEqual(int retryCount);

    @Query("SELECT pe.status, " +
            "CASE " +
            "WHEN pe.lastStatusChangedAt >= :fiveMinutesAgo THEN 'recent' " +
            "WHEN pe.lastStatusChangedAt >= :thirtyMinutesAgo AND pe.lastStatusChangedAt < :fiveMinutesAgo THEN 'medium' " +
            "ELSE 'old' " +
            "END, " +
            "COUNT(pe) " +
            "FROM PaymentEventEntity pe " +
            "GROUP BY pe.status, " +
            "CASE " +
            "WHEN pe.lastStatusChangedAt >= :fiveMinutesAgo THEN 'recent' " +
            "WHEN pe.lastStatusChangedAt >= :thirtyMinutesAgo AND pe.lastStatusChangedAt < :fiveMinutesAgo THEN 'medium' " +
            "ELSE 'old' " +
            "END")
    List<Object[]> countByStatusAndAgeBucketsGrouped(
            @Param("fiveMinutesAgo") LocalDateTime fiveMinutesAgo,
            @Param("thirtyMinutesAgo") LocalDateTime thirtyMinutesAgo
    );

    @Query("SELECT COUNT(pe) FROM PaymentEventEntity pe WHERE pe.status = 'READY' AND pe.createdAt < :expirationThreshold")
    long countNearExpiration(@Param("expirationThreshold") LocalDateTime expirationThreshold);
}
