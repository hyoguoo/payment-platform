package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOutboxEntity;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaPaymentOutboxRepository extends JpaRepository<PaymentOutboxEntity, Long> {

    Optional<PaymentOutboxEntity> findByOrderId(String orderId);

    /**
     * orderId UNIQUE INSERT IGNORE — 반영 행 수를 반환한다.
     * IGNORE로 중복 row가 있어도 예외 없이 통과하고, 반영 행 수 0으로 호출자가 중복 여부를 판정한다.
     * native query 사용 이유: JPQL 은 INSERT IGNORE 를 지원하지 않는다.
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO payment_outbox "
            + "(order_id, status, retry_count, created_at, updated_at) "
            + "VALUES (:orderId, 'PENDING', 0, :now, :now)",
            nativeQuery = true)
    int insertIgnorePending(@Param("orderId") String orderId, @Param("now") LocalDateTime now);

    /**
     * 확인 조회 — 블로킹 쓰기 잠금 읽기(FOR UPDATE).
     * insertIgnorePending 의 반영 행이 0일 때, 확정 트랜잭션이 이미 잡은 읽기 스냅샷을 우회해
     * 앞선 요청이 커밋한 최신 값을 보기 위해 사용한다. 워커 선점용 SKIP LOCKED 와는 다르다 —
     * 건너뛰기 힌트를 붙이면 앞선 요청의 커밋을 기다리지 않아 이 조회의 목적이 깨진다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM PaymentOutboxEntity e WHERE e.orderId = :orderId")
    Optional<PaymentOutboxEntity> findByOrderIdForUpdate(@Param("orderId") String orderId);

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

    // ── 관측 지표 집계 (Prometheus gauge) ───────────────────────────────────────

    @Query("SELECT COUNT(e) FROM PaymentOutboxEntity e WHERE e.status = 'PENDING'")
    long countPending();

    @Query("SELECT COUNT(e) FROM PaymentOutboxEntity e WHERE e.status = 'PENDING' AND e.nextRetryAt IS NOT NULL AND e.nextRetryAt > :now")
    long countFuturePending(@Param("now") LocalDateTime now);

    @Query("SELECT MIN(e.createdAt) FROM PaymentOutboxEntity e WHERE e.status = 'PENDING'")
    Optional<LocalDateTime> findOldestPendingCreatedAt();
}
