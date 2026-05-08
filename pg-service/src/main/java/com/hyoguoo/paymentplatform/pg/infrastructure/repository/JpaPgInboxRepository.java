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
     * PENDING → IN_PROGRESS compare-and-set.
     * 이미 존재하는 row의 status가 PENDING인 경우에만 IN_PROGRESS로 전이한다.
     * enum 파라미터를 사용하여 컴파일러가 명칭 변경을 감지할 수 있도록 한다.
     * TODO PCS-9: 메서드명 casNoneToInProgress → casPendingToInProgress 로 변경 예정
     *
     * @return 1 = 전이 성공, 0 = 이미 PENDING이 아님 (또는 row 부재)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PgInboxEntity e SET e.status = :inProgress, e.updatedAt = :now "
            + "WHERE e.orderId = :orderId AND e.status = :none")
    int casNoneToInProgress(@Param("orderId") String orderId,
                            @Param("now") LocalDateTime now,
                            @Param("none") PgInboxStatus none,
                            @Param("inProgress") PgInboxStatus inProgress);

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
     * 이미 terminal(APPROVED/FAILED/QUARANTINED)인 경우 0을 반환한다 (중복 DLQ 흡수, 불변식 6c).
     * enum 파라미터 사용.
     * TODO PCS-9: NONE 폐기 후 PENDING 파라미터로 봉합됨 — 주석 정합 완료
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

    // ─── PCS-4: 신규 메서드 ────────────────────────────────────────────────────

    /**
     * orderId UNIQUE INSERT IGNORE + 기존 id 조회.
     * IGNORE로 중복 row가 있어도 예외 없이 통과하고, 조회로 기존 id를 반환한다.
     * native query 사용 이유: JPQL 은 INSERT IGNORE 를 지원하지 않는다.
     * TODO PCS-X: event_uuid, vendor_type, payment_key 컬럼 스키마 추가 시 INSERT 에 포함할 것
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO pg_inbox (order_id, status, amount, created_at, updated_at) "
            + "VALUES (:orderId, 'PENDING', :amount, :now, :now)",
            nativeQuery = true)
    void insertIgnorePending(@Param("orderId") String orderId,
                             @Param("amount") long amount,
                             @Param("now") LocalDateTime now);

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
}
