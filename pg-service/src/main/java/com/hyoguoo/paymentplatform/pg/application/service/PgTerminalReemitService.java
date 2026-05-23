package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.event.PgOutboxReadyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * terminal inbox 재발행 서비스.
 *
 * <p>{@code PgConfirmService} 가 {@code @Transactional} 메서드를
 * self-invocation 하면 Spring proxy 를 우회해 TX 경계가 무시된다.
 * 별도 빈으로 분리하여 proxy 경유를 보장한다.
 *
 * <p>pg_outbox save + publishEvent(PgOutboxReadyEvent) 가 동일 active TX 안에서
 * 실행되어야 {@code @TransactionalEventListener(AFTER_COMMIT)} 가 등록된다.
 * storedStatusResult 없으면 warn 로그 후 즉시 return (outbox INSERT 미발생).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgTerminalReemitService {

    private final PgOutboxRepository pgOutboxRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * terminal inbox 의 storedStatusResult 를 재발행한다.
     *
     * @param inbox terminal 상태 inbox (storedStatusResult 포함)
     */
    @Transactional
    public void reemit(PgInbox inbox) {
        String storedResult = inbox.getStoredStatusResult();
        if (storedResult == null || storedResult.isBlank()) {
            LogFmt.warn(log, LogDomain.PG, EventType.PG_CONFIRM_TERMINAL_REEMIT,
                    () -> "orderId=" + inbox.getOrderId()
                            + " status=" + inbox.getStatus()
                            + " — storedStatusResult 없음");
            return;
        }

        PgOutbox reemit = PgOutbox.create(
                PgTopics.EVENTS_CONFIRMED,
                inbox.getOrderId(),
                storedResult,
                null);
        PgOutbox saved = pgOutboxRepository.save(reemit);
        applicationEventPublisher.publishEvent(new PgOutboxReadyEvent(saved.getId()));

        LogFmt.info(log, LogDomain.PG, EventType.PG_CONFIRM_TERMINAL_REEMIT,
                () -> "orderId=" + inbox.getOrderId() + " status=" + inbox.getStatus());
    }
}
