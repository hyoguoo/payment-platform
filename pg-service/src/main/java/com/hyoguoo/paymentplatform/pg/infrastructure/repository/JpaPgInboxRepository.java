package com.hyoguoo.paymentplatform.pg.infrastructure.repository;

import com.hyoguoo.paymentplatform.pg.infrastructure.entity.PgInboxEntity;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA — pg_inbox 테이블 접근.
 * CAS 전이는 JPQL UPDATE로 구현한다 (ADR-04, ADR-21).
 */
public interface JpaPgInboxRepository extends JpaRepository<PgInboxEntity, Long> {

    Optional<PgInboxEntity> findByOrderId(String orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM PgInboxEntity e WHERE e.orderId = :orderId")
    Optional<PgInboxEntity> findByOrderIdForUpdate(@Param("orderId") String orderId);

    /**
     * NONE → IN_PROGRESS compare-and-set.
     * 이미 존재하는 row의 status가 NONE인 경우에만 IN_PROGRESS로 전이한다.
     *
     * @return 1 = 전이 성공, 0 = 이미 NONE이 아님 (또는 row 부재)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PgInboxEntity e SET e.status = 'IN_PROGRESS', e.updatedAt = :now "
            + "WHERE e.orderId = :orderId AND e.status = 'NONE'")
    int casNoneToInProgress(@Param("orderId") String orderId, @Param("now") LocalDateTime now);

    /**
     * IN_PROGRESS → APPROVED.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PgInboxEntity e SET e.status = 'APPROVED', e.storedStatusResult = :result, "
            + "e.updatedAt = :now WHERE e.orderId = :orderId AND e.status = 'IN_PROGRESS'")
    int casInProgressToApproved(@Param("orderId") String orderId,
                                @Param("result") String storedStatusResult,
                                @Param("now") LocalDateTime now);

    /**
     * IN_PROGRESS → FAILED.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PgInboxEntity e SET e.status = 'FAILED', e.storedStatusResult = :result, "
            + "e.reasonCode = :reasonCode, e.updatedAt = :now "
            + "WHERE e.orderId = :orderId AND e.status = 'IN_PROGRESS'")
    int casInProgressToFailed(@Param("orderId") String orderId,
                              @Param("result") String storedStatusResult,
                              @Param("reasonCode") String reasonCode,
                              @Param("now") LocalDateTime now);

    /**
     * non-terminal(NONE/IN_PROGRESS) → QUARANTINED.
     * 이미 terminal(APPROVED/FAILED/QUARANTINED)인 경우 0을 반환한다 (중복 DLQ 흡수, 불변식 6c).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PgInboxEntity e SET e.status = 'QUARANTINED', e.reasonCode = :reasonCode, "
            + "e.updatedAt = :now WHERE e.orderId = :orderId "
            + "AND e.status IN ('NONE', 'IN_PROGRESS')")
    int casNonTerminalToQuarantined(@Param("orderId") String orderId,
                                    @Param("reasonCode") String reasonCode,
                                    @Param("now") LocalDateTime now);
}
