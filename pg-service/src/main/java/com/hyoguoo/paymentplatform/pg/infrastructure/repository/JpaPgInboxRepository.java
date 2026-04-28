package com.hyoguoo.paymentplatform.pg.infrastructure.repository;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
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
 * compare-and-set 전이는 JPQL UPDATE 로 구현한다.
 */
public interface JpaPgInboxRepository extends JpaRepository<PgInboxEntity, Long> {

    Optional<PgInboxEntity> findByOrderId(String orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM PgInboxEntity e WHERE e.orderId = :orderId")
    Optional<PgInboxEntity> findByOrderIdForUpdate(@Param("orderId") String orderId);

    /**
     * NONE → IN_PROGRESS compare-and-set.
     * 이미 존재하는 row의 status가 NONE인 경우에만 IN_PROGRESS로 전이한다.
     * enum 파라미터를 사용하여 컴파일러가 명칭 변경을 감지할 수 있도록 한다.
     *
     * @return 1 = 전이 성공, 0 = 이미 NONE이 아님 (또는 row 부재)
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
     * non-terminal(NONE/IN_PROGRESS) → QUARANTINED.
     * 이미 terminal(APPROVED/FAILED/QUARANTINED)인 경우 0을 반환한다 (중복 DLQ 흡수, 불변식 6c).
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
}
