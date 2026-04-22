package com.hyoguoo.paymentplatform.pg.infrastructure.repository;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.infrastructure.entity.PgInboxEntity;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PgInboxRepository 포트 JPA 어댑터.
 * ADR-04(2단 멱등성) + ADR-21(5상태 business inbox) 계약을 DB-레벨 compare-and-set + FOR UPDATE로 이행한다.
 *
 * <p>{@link #transitNoneToInProgress(String, long)}는 row 부재 시 INSERT로 선점하고,
 * 존재 시 JPQL UPDATE의 status=NONE 조건으로 CAS한다.
 */
@Repository
@RequiredArgsConstructor
public class PgInboxRepositoryImpl implements PgInboxRepository {

    private final JpaPgInboxRepository jpaPgInboxRepository;

    @Override
    public Optional<PgInbox> findByOrderId(String orderId) {
        return jpaPgInboxRepository.findByOrderId(orderId).map(PgInboxEntity::toDomain);
    }

    @Override
    public PgInbox save(PgInbox inbox) {
        return jpaPgInboxRepository.save(PgInboxEntity.from(inbox)).toDomain();
    }

    /**
     * NONE → IN_PROGRESS 원자 전이.
     * row 부재 시: IN_PROGRESS row로 직접 INSERT 선점.
     * row 존재 시: status=NONE 조건의 JPQL UPDATE(CAS) 성공 여부로 판정.
     *
     * <p>순서 이유: 이미 IN_PROGRESS/APPROVED/FAILED/QUARANTINED인 row에 INSERT하면 UNIQUE 충돌이 발생하므로,
     * 먼저 find → 부재 시에만 INSERT, 존재 시 CAS UPDATE로 분기한다.
     */
    @Override
    @Transactional
    public boolean transitNoneToInProgress(String orderId, long amount) {
        Optional<PgInboxEntity> existing = jpaPgInboxRepository.findByOrderId(orderId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        if (existing.isEmpty()) {
            PgInbox inProgress = PgInbox.of(
                    orderId, PgInboxStatus.IN_PROGRESS, amount,
                    null, null,
                    now.toInstant(ZoneOffset.UTC), now.toInstant(ZoneOffset.UTC));
            jpaPgInboxRepository.save(PgInboxEntity.from(inProgress));
            return true;
        }

        return jpaPgInboxRepository.casNoneToInProgress(orderId, now) > 0;
    }

    @Override
    @Transactional
    public void transitToApproved(String orderId, String storedStatusResult) {
        jpaPgInboxRepository.casInProgressToApproved(
                orderId, storedStatusResult, LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    @Transactional
    public void transitToFailed(String orderId, String storedStatusResult, String reasonCode) {
        jpaPgInboxRepository.casInProgressToFailed(
                orderId, storedStatusResult, reasonCode, LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    @Transactional
    public boolean transitToQuarantined(String orderId, String reasonCode) {
        return jpaPgInboxRepository.casNonTerminalToQuarantined(
                orderId, reasonCode, LocalDateTime.now(ZoneOffset.UTC)) > 0;
    }

    @Override
    public Optional<PgInbox> findByOrderIdForUpdate(String orderId) {
        return jpaPgInboxRepository.findByOrderIdForUpdate(orderId).map(PgInboxEntity::toDomain);
    }

}
