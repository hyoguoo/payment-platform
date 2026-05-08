package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.port.in.PgInboxProcessUseCase;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * PgInboxProcessUseCase 구현체 — PCS-8.
 *
 * <p>워커({@code PgInboxImmediateWorker} / {@code PgInboxPollingWorker}) 가 호출하는 두 진입점:
 * <ul>
 *   <li>{@link #processPending} — 정상 흐름 신규 진입 / PENDING 좀비 회수</li>
 *   <li>{@link #processInProgressZombie} — IN_PROGRESS 좀비 회수 / ALREADY_PROCESSED 보정 경로</li>
 * </ul>
 *
 * <p>TX 시퀀스:
 * <ol>
 *   <li>TX_A: PENDING→IN_PROGRESS CAS (SKIP LOCKED). 0 row → 즉시 return (다른 워커 선점).</li>
 *   <li>벤더 HTTP (TX 외부): {@link PgVendorCallService#invokeVendor} 호출.
 *       VT 캐리어 양보, DB 커넥션 자유.</li>
 *   <li>TX_B: {@link PgVendorCallService#applyOutcome} 호출. 5분기 처리
 *       (Success / Retryable / NonRetryable / HandledInternally / DLQ).</li>
 * </ol>
 *
 * <p>벤더 RuntimeException 시 TX_B 미진입 → pg_inbox IN_PROGRESS 잔존
 * → 좀비 폴링({@code PgInboxPollingWorker}) 이 {@link #processInProgressZombie} 로 회수.
 * ALREADY_PROCESSED 응답은 HandledInternally outcome 으로 변환되어
 * {@code applyOutcome} 의 5분기 안에서 {@code DuplicateApprovalHandler} 에 위임된다.
 *
 * <p>현재 스키마 제약: {@code pg_inbox} 에 {@code paymentKey} / {@code vendorType} 컬럼 없음.
 * {@link PgConfirmRequest} 는 orderId + amount 만으로 임시 구성하며,
 * paymentKey={@code null}, vendorType={@code null} 로 설정된다.
 * TODO PCS-X: {@code pg_inbox} 스키마에 vendorType / paymentKey 컬럼 추가 후 정합 필요.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgInboxProcessor implements PgInboxProcessUseCase {

    private final PgInboxRepository inboxRepository;
    private final PgVendorCallService vendorCallService;

    // -----------------------------------------------------------------------
    // processPending — TX_A PENDING→IN_PROGRESS CAS + 벤더 HTTP + TX_B
    // -----------------------------------------------------------------------

    /**
     * PENDING 상태의 inbox row 를 처리한다.
     *
     * <p>TX_A: SKIP LOCKED SELECT → PENDING→IN_PROGRESS 전환.
     * 0 row (다른 워커 선점 또는 이미 처리됨) → return.
     * 1 row (선점 성공) → 벤더 HTTP (TX 밖) → TX_B 결과 반영.
     *
     * @param inboxId pg_inbox.id
     */
    @Override
    public void processPending(Long inboxId) {
        // inbox 조회 — orderId / amount 등 벤더 호출에 필요한 데이터 확보
        Optional<PgInbox> inboxOpt = inboxRepository.findById(inboxId);
        if (inboxOpt.isEmpty()) {
            LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_INBOX_WORKER_SKIP,
                    () -> "inboxId=" + inboxId + " reason=NOT_FOUND");
            return;
        }
        PgInbox inbox = inboxOpt.get();

        // TX_A: PENDING→IN_PROGRESS CAS (SKIP LOCKED)
        boolean acquired = inboxRepository.transitPendingToInProgress(inboxId);
        if (!acquired) {
            // 다른 워커가 이미 선점하거나 PENDING 이 아닌 상태 — 정상 종료
            LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_INBOX_WORKER_SKIP,
                    () -> "inboxId=" + inboxId + " reason=PREEMPTED_OR_NOT_PENDING");
            return;
        }

        LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_INBOX_WORKER_START,
                () -> "inboxId=" + inboxId + " orderId=" + inbox.getOrderId());

        PgConfirmRequest request = buildRequest(inbox);

        // 벤더 HTTP (TX 밖) — RuntimeException 시 TX_B 미진입, IN_PROGRESS 잔존 → 좀비 폴링 회수
        GatewayOutcome outcome = vendorCallService.invokeVendor(request);

        // TX_B — 벤더 응답 5분기 처리
        vendorCallService.applyOutcome(outcome, request, resolveAttempt(inbox), Instant.now());
    }

    // -----------------------------------------------------------------------
    // processInProgressZombie — IN_PROGRESS row 재처리 (TX_A 생략)
    // -----------------------------------------------------------------------

    /**
     * IN_PROGRESS 좀비 상태의 inbox row 를 재처리한다.
     *
     * <p>TX_A 생략 — 이미 IN_PROGRESS 인 row 를 그대로 사용한다.
     * row 부재 시 즉시 return.
     * 벤더 재호출 시 idempotency-key 로 ALREADY_PROCESSED 응답 가능 →
     * {@code applyOutcome} 의 HandledInternally 분기 → {@code DuplicateApprovalHandler} 위임.
     *
     * @param inboxId pg_inbox.id
     */
    @Override
    public void processInProgressZombie(Long inboxId) {
        Optional<PgInbox> inboxOpt = inboxRepository.findById(inboxId);
        if (inboxOpt.isEmpty()) {
            LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_INBOX_WORKER_SKIP,
                    () -> "inboxId=" + inboxId + " reason=NOT_FOUND");
            return;
        }
        PgInbox inbox = inboxOpt.get();

        // IN_PROGRESS 가 아닌 row 는 이미 종결 → 정상 종료
        if (inbox.getStatus() != PgInboxStatus.IN_PROGRESS) {
            LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_INBOX_WORKER_SKIP,
                    () -> "inboxId=" + inboxId + " status=" + inbox.getStatus() + " reason=NOT_IN_PROGRESS");
            return;
        }

        LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_INBOX_WORKER_START,
                () -> "inboxId=" + inboxId + " orderId=" + inbox.getOrderId() + " zombie=true");

        PgConfirmRequest request = buildRequest(inbox);

        // 벤더 재호출 (멱등성 3단 layer — ALREADY_PROCESSED 응답 가능)
        GatewayOutcome outcome = vendorCallService.invokeVendor(request);

        // TX_B — 5분기 처리. HandledInternally → DuplicateApprovalHandler 보정 경로
        vendorCallService.applyOutcome(outcome, request, resolveAttempt(inbox), Instant.now());
    }

    // -----------------------------------------------------------------------
    // 내부 헬퍼
    // -----------------------------------------------------------------------

    /**
     * PgInbox 에서 PgConfirmRequest 를 구성한다.
     *
     * <p>PCS-9 V3 migration: pg_inbox 에 paymentKey / vendorType 컬럼 추가됨.
     * inbox 에서 직접 읽어 PgConfirmRequest 를 구성한다.
     * vendorType 은 String → PgVendorType 변환 (null-safe).
     *
     * @param inbox pg_inbox row (paymentKey / vendorType 포함)
     * @return PgConfirmRequest
     */
    private PgConfirmRequest buildRequest(PgInbox inbox) {
        BigDecimal amount = inbox.getAmount() != null
                ? BigDecimal.valueOf(inbox.getAmount())
                : BigDecimal.ZERO;
        PgVendorType vendorType = inbox.getVendorType() != null
                ? PgVendorType.valueOf(inbox.getVendorType())
                : null;
        return new PgConfirmRequest(inbox.getOrderId(), inbox.getPaymentKey(), amount, vendorType);
    }

    /**
     * attempt 번호 결정 — 현재 스키마에 attempt 컬럼 없으므로 1 고정.
     */
    private int resolveAttempt(PgInbox inbox) {
        return 1;
    }
}
