package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaPaymentEventRepository extends JpaRepository<PaymentEventEntity, Long> {

    Optional<PaymentEventEntity> findByOrderId(String orderId);

    // BaseEntity.createdAt 은 Instant(DATETIME(6)) 컬럼이며, Instant 파라미터를
    // Hibernate 가 hibernate.jdbc.time_zone=UTC 기준으로 UTC Calendar 바인딩하므로
    // JdbcTemplate 경로(connectionTimeZone=UTC)와 동일한 UTC 기준으로 비교된다.
    // PaymentEventRepositoryImpl 에서 변환 없이 Instant 를 직접 전달.
    @Query(value = "SELECT * FROM payment_event WHERE status = 'READY' AND created_at < :before",
            nativeQuery = true)
    List<PaymentEventEntity> findReadyPaymentsOlderThan(@Param("before") Instant before);

    @Query("SELECT pe.status, COUNT(pe) FROM PaymentEventEntity pe GROUP BY pe.status")
    List<Object[]> countByStatusGrouped();

    @Query("SELECT COUNT(pe) FROM PaymentEventEntity pe WHERE pe.status = :status AND pe.executedAt < :before")
    long countByStatusAndExecutedAtBefore(@Param("status") PaymentEventStatus status,
            @Param("before") Instant before);

    @Query("SELECT pe FROM PaymentEventEntity pe WHERE pe.status = 'IN_PROGRESS' AND pe.executedAt < :before")
    List<PaymentEventEntity> findInProgressOlderThan(@Param("before") Instant before);

    List<PaymentEventEntity> findByStatus(PaymentEventStatus status);

    // 격리 복구 CAS 게이트 — WHERE status = 'QUARANTINED' 조건이 만족될 때만 반영되며,
    // affected rows(0 또는 1)를 반환한다. PaymentEventRepositoryImpl 이 이 값을 보고
    // 자식 payment_order 동조 갱신 여부를 결정한다.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PaymentEventEntity e SET e.status = 'FAILED', e.statusReason = :reason, "
            + "e.lastStatusChangedAt = :lastStatusChangedAt "
            + "WHERE e.id = :id AND e.status = 'QUARANTINED'")
    int resolveQuarantineToFailed(@Param("id") Long id,
            @Param("reason") String reason,
            @Param("lastStatusChangedAt") Instant lastStatusChangedAt);
}
