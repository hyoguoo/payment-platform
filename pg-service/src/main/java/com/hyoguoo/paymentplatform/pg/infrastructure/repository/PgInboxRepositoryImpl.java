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
 * <p>주요 메서드:
 * <ul>
 *   <li>{@link #insertPending} — orderId UNIQUE INSERT IGNORE + id 반환 (listener 경로 PENDING 시작)</li>
 *   <li>{@link #transitPendingToInProgress} — SKIP LOCKED SELECT + UPDATE (워커 TX_A)</li>
 *   <li>{@link #transitDirectToInProgress} — PENDING 우회 IN_PROGRESS 직진 (보정 경로)</li>
 *   <li>{@link #transitDirectToTerminal} — PENDING + IN_PROGRESS 우회 terminal 직진 (보정 경로)</li>
 *   <li>{@link #findPendingZombieIds} / {@link #findInProgressZombieIds} — 좀비 폴링 조회</li>
 *   <li>{@link #selectInProgressForUpdateSkipLocked} — IN_PROGRESS SKIP LOCKED 단건 선점</li>
 * </ul>
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
        // 시간 결정성 위해 LocalDateTime.now(clock) 사용
        // PENDING / IN_PROGRESS non-terminal 상태만 격리 대상
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
     * <p>paymentKey / vendorType / storedTraceparent 컬럼을 포함하여 INSERT 한다.
     * eventUuid 는 DB 컬럼 없이 EventDedupeStore 에서 관리하므로 여기서는 무시한다.
     * storedTraceparent 는 NULL 허용 — 헤더 부재·구버전 행 호환.
     */
    @Override
    @Transactional
    public Long insertPending(String orderId, long amount, String eventUuid,
                              String vendorType, String paymentKey, String storedTraceparent) {
        LocalDateTime now = LocalDateTime.now(clock);
        jpaPgInboxRepository.insertIgnorePending(orderId, amount, paymentKey, vendorType, storedTraceparent, now);
        return jpaPgInboxRepository.findIdByOrderId(orderId);
    }

    /**
     * 회수 경로 전용 — stored_traceparent 단일 컬럼 조회.
     * NULL 또는 행 없음이면 Optional.empty() 반환.
     */
    @Override
    public Optional<String> findStoredTraceparent(Long inboxId) {
        return jpaPgInboxRepository.findStoredTraceparentById(inboxId);
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
        PgInbox inbox = PgInbox.createDirectInProgress(orderId, amount, clock.instant());
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

    /**
     * IN_PROGRESS row 단건 SKIP LOCKED 선점.
     *
     * <p>{@code SELECT FOR UPDATE SKIP LOCKED WHERE id=? AND status='IN_PROGRESS'} 로
     * 다른 워커가 동시에 동일 row 를 처리하는 경우 선점 실패(빈 결과) → Optional.empty() 반환.
     * 선점 성공 시 {@link PgInboxEntity#toDomain()} 으로 변환하여 반환한다.
     */
    @Override
    @Transactional
    public Optional<PgInbox> selectInProgressForUpdateSkipLocked(Long inboxId) {
        Optional<Long> locked = jpaPgInboxRepository.selectForUpdateSkipLockedInProgress(inboxId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        return jpaPgInboxRepository.findById(inboxId).map(PgInboxEntity::toDomain);
    }

}
