package com.hyoguoo.paymentplatform.pg.infrastructure.repository;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.infrastructure.entity.PgInboxEntity;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PgInboxRepository 포트 JPA 어댑터.
 * 2단 멱등성 + 5상태 business inbox 계약을 DB-레벨 compare-and-set + FOR UPDATE 로 이행한다.
 *
 * <p>{@link #transitNoneToInProgress(String, long)} 는 row 부재 시 INSERT 로 선점하고,
 * 존재 시 JPQL UPDATE 의 status=NONE 조건으로 CAS 한다.
 */
@Repository
@RequiredArgsConstructor
public class PgInboxRepositoryImpl implements PgInboxRepository {

    private final JpaPgInboxRepository jpaPgInboxRepository;
    /**
     * Clock 주입으로 시간 결정성 확보 — {@code LocalDateTime.now(ZoneOffset.UTC)} 직접 호출을 금지한다.
     * {@link com.hyoguoo.paymentplatform.pg.infrastructure.config.PgServiceConfig} 에서
     * {@code Clock.systemUTC()} Bean 으로 주입한다.
     */
    private final Clock clock;

    @Override
    public Optional<PgInbox> findByOrderId(String orderId) {
        return jpaPgInboxRepository.findByOrderId(orderId).map(PgInboxEntity::toDomain);
    }

    @Override
    public Optional<PgInbox> findById(Long inboxId) {
        return jpaPgInboxRepository.findById(inboxId).map(PgInboxEntity::toDomain);
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
        // 시간 결정성 위해 clock.instant() / LocalDateTime.now(clock) 사용
        LocalDateTime now = LocalDateTime.now(clock);

        if (existing.isEmpty()) {
            // TODO PCS-9: transitNoneToInProgress 자체가 PCS-9 에서 제거/교체 예정
            // 임시 봉합: PgInbox.createDirectInProgress 팩토리 사용 (PENDING 우회 — 보정 경로 패턴 차용)
            PgInbox inProgress = PgInbox.createDirectInProgress(orderId, amount);
            jpaPgInboxRepository.save(PgInboxEntity.from(inProgress));
            return true;
        }

        // TODO PCS-9: NONE → PENDING 전이 정합 정정 예정 — 현재 임시 봉합 (NONE 폐기, PENDING 으로 대체)
        return jpaPgInboxRepository.casNoneToInProgress(
                orderId, now, PgInboxStatus.PENDING, PgInboxStatus.IN_PROGRESS) > 0;
    }

    @Override
    @Transactional
    public void transitToApproved(String orderId, String storedStatusResult) {
        // 시간 결정성 위해 clock.instant() / LocalDateTime.now(clock) 사용
        jpaPgInboxRepository.casInProgressToApproved(
                orderId, storedStatusResult, LocalDateTime.now(clock),
                PgInboxStatus.IN_PROGRESS, PgInboxStatus.APPROVED);
    }

    @Override
    @Transactional
    public void transitToFailed(String orderId, String storedStatusResult, String reasonCode) {
        // 시간 결정성 위해 clock.instant() / LocalDateTime.now(clock) 사용
        jpaPgInboxRepository.casInProgressToFailed(
                orderId, storedStatusResult, reasonCode, LocalDateTime.now(clock),
                PgInboxStatus.IN_PROGRESS, PgInboxStatus.FAILED);
    }

    @Override
    @Transactional
    public boolean transitToQuarantined(String orderId, String reasonCode) {
        // 시간 결정성 위해 clock.instant() / LocalDateTime.now(clock) 사용
        // TODO PCS-9: NONE → PENDING 전이 정합 정정 예정 — casNonTerminalToQuarantined 파라미터 갱신
        return jpaPgInboxRepository.casNonTerminalToQuarantined(
                orderId, reasonCode, LocalDateTime.now(clock),
                PgInboxStatus.PENDING, PgInboxStatus.IN_PROGRESS, PgInboxStatus.QUARANTINED) > 0;
    }

    @Override
    public Optional<PgInbox> findByOrderIdForUpdate(String orderId) {
        return jpaPgInboxRepository.findByOrderIdForUpdate(orderId).map(PgInboxEntity::toDomain);
    }

    /**
     * listener 경로 — PENDING row INSERT IGNORE + 기존 또는 신규 id 반환.
     *
     * <p>INSERT IGNORE 로 orderId UNIQUE 충돌을 흡수하고, SELECT 로 실제 id 를 반환한다.
     * 신규 삽입 또는 기존 row — 어느 경우도 동일한 id 를 반환하여 downstream 이 inboxId 를 보유한다.
     *
     * <p>TODO PCS-X: eventUuid, vendorType, paymentKey 컬럼이 스키마에 추가되면
     * insertIgnorePending JPQL 과 PgInboxEntity 에 해당 필드를 포함할 것.
     * 현재는 포트 계약 시그니처만 존재하고 DB 컬럼이 없으므로 파라미터를 무시한다.
     */
    @Override
    @Transactional
    public Long insertPending(String orderId, long amount, String eventUuid,
                              String vendorType, String paymentKey) {
        LocalDateTime now = LocalDateTime.now(clock);
        jpaPgInboxRepository.insertIgnorePending(orderId, amount, now);
        return jpaPgInboxRepository.findIdByOrderId(orderId);
    }

    /**
     * 워커 TX_A — PENDING → IN_PROGRESS SKIP LOCKED 원자 전이.
     *
     * <p>{@code SELECT FOR UPDATE SKIP LOCKED WHERE id=? AND status=PENDING} 로
     * 다른 워커가 동시에 동일 row 를 처리하는 경우 선점 실패(빈 결과) → false 반환.
     * 선점 성공 시 동일 TX 내 UPDATE 로 IN_PROGRESS 전이.
     */
    @Override
    @Transactional
    public boolean transitPendingToInProgress(Long inboxId) {
        Optional<Long> locked = jpaPgInboxRepository.selectForUpdateSkipLockedPending(inboxId);
        if (locked.isEmpty()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = jpaPgInboxRepository.updatePendingToInProgress(
                inboxId, now, PgInboxStatus.PENDING, PgInboxStatus.IN_PROGRESS);
        return updated > 0;
    }

    /**
     * 보정 경로 — PENDING 우회, inbox 신설 + 바로 IN_PROGRESS.
     * {@link PgInbox#createDirectInProgress} 팩토리로 도메인 객체 생성 후 저장.
     */
    @Override
    @Transactional
    public Long transitDirectToInProgress(String orderId, long amount) {
        PgInbox inbox = PgInbox.createDirectInProgress(orderId, amount);
        return jpaPgInboxRepository.save(PgInboxEntity.from(inbox)).getId();
    }

    /**
     * 보정 경로 — PENDING + IN_PROGRESS 우회, inbox 신설 + 직접 terminal 전이.
     * {@link PgInbox#of} 로 terminal 상태 도메인 객체 생성 후 저장 (reasonCode 포함).
     * {@link PgInbox#createDirectTerminal} 은 reasonCode 파라미터가 없어 직접 of() 사용.
     */
    @Override
    @Transactional
    public Long transitDirectToTerminal(String orderId, long amount, PgInboxStatus terminalStatus,
                                        String storedStatusResult, String reasonCode) {
        if (!terminalStatus.isTerminal()) {
            throw new IllegalArgumentException(
                    "PgInboxRepositoryImpl.transitDirectToTerminal: status must be terminal but was " + terminalStatus);
        }
        java.time.Instant now = clock.instant();
        PgInbox inbox = PgInbox.of(orderId, terminalStatus, amount, storedStatusResult, reasonCode, now, now);
        return jpaPgInboxRepository.save(PgInboxEntity.from(inbox)).getId();
    }

    /**
     * 좀비 폴링 — PENDING 상태이며 임계 시간을 초과한 row id 목록 반환.
     * {@code received_at(created_at) < now - thresholdMs} 조건.
     */
    @Override
    public List<Long> findPendingZombieIds(int batchSize, long thresholdMs) {
        LocalDateTime before = LocalDateTime.now(clock).minusNanos(thresholdMs * 1_000_000L);
        return jpaPgInboxRepository.findPendingZombieIdsBefore(
                PgInboxStatus.PENDING, before, PageRequest.of(0, batchSize));
    }

    /**
     * 좀비 폴링 — IN_PROGRESS 상태이며 임계 시간을 초과한 row id 목록 반환.
     * {@code updated_at < now - thresholdMs} 조건.
     */
    @Override
    public List<Long> findInProgressZombieIds(int batchSize, long thresholdMs) {
        LocalDateTime before = LocalDateTime.now(clock).minusNanos(thresholdMs * 1_000_000L);
        return jpaPgInboxRepository.findInProgressZombieIdsBefore(
                PgInboxStatus.IN_PROGRESS, before, PageRequest.of(0, batchSize));
    }

}
