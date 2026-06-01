package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaPaymentEventRepository extends JpaRepository<PaymentEventEntity, Long> {

    Optional<PaymentEventEntity> findByOrderId(String orderId);

    // BaseEntity.createdAt 이 LocalDateTime(DATETIME) 이므로 native query 에서 문자열 비교.
    // JPQL 파라미터 바인딩 시 시스템 TZ 의존 문제를 피하기 위해 native query 사용.
    // 포트 시그니처는 Instant — PaymentEventRepositoryImpl 에서 UTC LocalDateTime 변환 후 전달.
    @Query(value = "SELECT * FROM payment_event WHERE status = 'READY' AND created_at < :before",
            nativeQuery = true)
    List<PaymentEventEntity> findReadyPaymentsOlderThan(@Param("before") LocalDateTime before);

    @Query("SELECT pe.status, COUNT(pe) FROM PaymentEventEntity pe GROUP BY pe.status")
    List<Object[]> countByStatusGrouped();

    @Query("SELECT COUNT(pe) FROM PaymentEventEntity pe WHERE pe.status = :status AND pe.executedAt < :before")
    long countByStatusAndExecutedAtBefore(@Param("status") PaymentEventStatus status,
            @Param("before") Instant before);

    long countByRetryCountGreaterThanEqual(int retryCount);

    @Query("SELECT pe FROM PaymentEventEntity pe WHERE pe.status = 'IN_PROGRESS' AND pe.executedAt < :before")
    List<PaymentEventEntity> findInProgressOlderThan(@Param("before") Instant before);

    List<PaymentEventEntity> findByStatus(PaymentEventStatus status);
}
