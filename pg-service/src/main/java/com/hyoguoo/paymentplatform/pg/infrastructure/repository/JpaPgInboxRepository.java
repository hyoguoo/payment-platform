package com.hyoguoo.paymentplatform.pg.infrastructure.repository;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.infrastructure.entity.PgInboxEntity;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA — pg_inbox 테이블 접근.
 * compare-and-set 전이는 JPQL UPDATE 로 구현한다.
 */
public interface JpaPgInboxRepository extends JpaRepository<PgInboxEntity, Long> {

    Optional<PgInboxEntity> findByOrderId(String orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM PgInboxEntity e WHERE e.orderId = :orderId")
    Optional<PgInboxEntity> findByOrderIdForUpdate(@Param("orderId") String orderId);

    /**
     * IN_PROGRESS → APPROVED.
     * enum 파라미터 사용.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PgInboxEntity e SET e.status = :approved, e.storedStatusResult = :result, "
            + "e.updatedAt = :now WHERE e.orderId = :orderId AND e.status = :inProgress")
    int casInProgressToApproved(@Param("orderId") String orderId,
                                @Param("result") String storedStatusResult,
                                @Param("now") LocalDateTime now,
                                @Param("inProgress") PgInboxStatus inProgress,
                                @Param("approved") PgInboxStatus approved);

    /**
     * IN_PROGRESS → FAILED.
     * enum 파라미터 사용.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PgInboxEntity e SET e.status = :failed, e.storedStatusResult = :result, "
            + "e.reasonCode = :reasonCode, e.updatedAt = :now "
            + "WHERE e.orderId = :orderId AND e.status = :inProgress")
    int casInProgressToFailed(@Param("orderId") String orderId,
                              @Param("result") String storedStatusResult,
                              @Param("reasonCode") String reasonCode,
                              @Param("now") LocalDateTime now,
                              @Param("inProgress") PgInboxStatus inProgress,
                              @Param("failed") PgInboxStatus failed);

    /**
     * non-terminal(PENDING/IN_PROGRESS) → QUARANTINED.
     * 이미 terminal(APPROVED/FAILED/QUARANTINED)인 경우 0을 반환한다 (중복 DLQ 흡수).
     * enum 파라미터 사용.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PgInboxEntity e SET e.status = :quarantined, e.reasonCode = :reasonCode, "
            + "e.updatedAt = :now WHERE e.orderId = :orderId "
            + "AND e.status IN (:none, :inProgress)")
    int casNonTerminalToQuarantined(@Param("orderId") String orderId,
                                    @Param("reasonCode") String reasonCode,
                                    @Param("now") LocalDateTime now,
                                    @Param("none") PgInboxStatus none,
                                    @Param("inProgress") PgInboxStatus inProgress,
                                    @Param("quarantined") PgInboxStatus quarantined);

    // ─── listener / 워커 / 좀비 폴링 경로 메서드 ────────────────────────────────

    /**
     * orderId UNIQUE INSERT IGNORE + 기존 id 조회.
     * IGNORE로 중복 row가 있어도 예외 없이 통과하고, 조회로 기존 id를 반환한다.
     * native query 사용 이유: JPQL 은 INSERT IGNORE 를 지원하지 않는다.
     * payment_key / vendor_type / stored_traceparent 컬럼을 포함하여 INSERT 한다.
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO pg_inbox "
            + "(order_id, status, amount, payment_key, vendor_type, stored_traceparent, created_at, updated_at) "
            + "VALUES (:orderId, 'PENDING', :amount, :paymentKey, :vendorType, :storedTraceparent, :now, :now)",
            nativeQuery = true)
    void insertIgnorePending(@Param("orderId") String orderId,
                             @Param("amount") long amount,
                             @Param("paymentKey") String paymentKey,
                             @Param("vendorType") String vendorType,
                             @Param("storedTraceparent") String storedTraceparent,
                             @Param("now") LocalDateTime now);

    /**
     * stored_traceparent 단일 컬럼 조회 — 회수 경로 전용.
     * PgInboxPollingWorker 가 부모 추적 복원 시 사용한다.
     */
    @Query("SELECT e.storedTraceparent FROM PgInboxEntity e WHERE e.id = :inboxId")
    Optional<String> findStoredTraceparentById(@Param("inboxId") Long inboxId);

    /**
     * orderId로 id 조회 — insertIgnorePending 이후 실제 id 반환용.
     */
    @Query("SELECT e.id FROM PgInboxEntity e WHERE e.orderId = :orderId")
    Long findIdByOrderId(@Param("orderId") String orderId);

    /**
     * 워커 TX_A SKIP LOCKED — PENDING row 선점.
     * {@code SELECT FOR UPDATE SKIP LOCKED WHERE id=? AND status=PENDING} 로 원자 선점.
     * 다른 워커가 이미 잠근 경우(SKIP LOCKED) 빈 결과 반환 → false 처리.
     * nativeQuery 사용 이유: JPQL 은 SKIP LOCKED 를 지원하지 않는다.
     */
    @Query(value = "SELECT id FROM pg_inbox WHERE id = :inboxId AND status = 'PENDING' "
            + "FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<Long> selectForUpdateSkipLockedPending(@Param("inboxId") Long inboxId);

    /**
     * id 기준 PENDING → IN_PROGRESS CAS UPDATE.
     * selectForUpdateSkipLockedPending 이후 동일 TX 내에서 호출한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PgInboxEntity e SET e.status = :inProgress, e.updatedAt = :now "
            + "WHERE e.id = :inboxId AND e.status = :pending")
    int updatePendingToInProgress(@Param("inboxId") Long inboxId,
                                  @Param("now") LocalDateTime now,
                                  @Param("pending") PgInboxStatus pending,
                                  @Param("inProgress") PgInboxStatus inProgress);

    /**
     * 좀비 PENDING 조회 — received_at(created_at) < :before 인 PENDING row id 목록.
     */
    @Query("SELECT e.id FROM PgInboxEntity e "
            + "WHERE e.status = :pending AND e.createdAt < :before "
            + "ORDER BY e.createdAt ASC")
    List<Long> findPendingZombieIdsBefore(@Param("pending") PgInboxStatus pending,
                                          @Param("before") LocalDateTime before,
                                          org.springframework.data.domain.Pageable pageable);

    /**
     * 좀비 IN_PROGRESS 조회 — updated_at < :before 인 IN_PROGRESS row id 목록.
     */
    @Query("SELECT e.id FROM PgInboxEntity e "
            + "WHERE e.status = :inProgress AND e.updatedAt < :before "
            + "ORDER BY e.updatedAt ASC")
    List<Long> findInProgressZombieIdsBefore(@Param("inProgress") PgInboxStatus inProgress,
                                             @Param("before") LocalDateTime before,
                                             org.springframework.data.domain.Pageable pageable);

    /**
     * IN_PROGRESS row 단건 SKIP LOCKED 선점.
     *
     * <p>{@code SELECT id FROM pg_inbox WHERE id=? AND status='IN_PROGRESS' FOR UPDATE SKIP LOCKED}.
     * 다른 워커가 이미 잠근 경우(SKIP LOCKED) 빈 결과 반환 → processInProgressZombie silent return.
     * nativeQuery 사용 이유: JPQL 은 SKIP LOCKED 를 지원하지 않는다.
     *
     * <p>주의: 이 메서드는 반드시 active 트랜잭션 안에서 호출해야 SKIP LOCKED 효과가 적용된다.
     * {@link com.hyoguoo.paymentplatform.pg.infrastructure.repository.PgInboxRepositoryImpl#selectInProgressForUpdateSkipLocked}
     * 에서 {@code @Transactional} 경계를 제공한다.
     */
    @Query(value = "SELECT id FROM pg_inbox WHERE id = :inboxId AND status = 'IN_PROGRESS' "
            + "FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<Long> selectForUpdateSkipLockedInProgress(@Param("inboxId") Long inboxId);
}
