package com.hyoguoo.paymentplatform.pg.infrastructure.listener;

import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.event.PgInboxReadyEvent;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.PgInboxChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * pg_inbox row DB 커밋 이후 PgInboxChannel 에 inboxId 를 전달하는 AFTER_COMMIT 리스너.
 *
 * <p>{@link OutboxReadyEventHandler} 1:1 거울 위치 (outbox 발행 측 ↔ inbox 수신 측).
 *
 * <p>동작:
 * <ol>
 *   <li>PgInboxReadyEvent 수신 (AFTER_COMMIT — DB 커밋 이후 호출 불변).</li>
 *   <li>PgInboxChannel.offerNow(inboxId) 호출 — offer 시점에서 OTel Context + MDC snapshot 캡처 포함.</li>
 *   <li>offer 실패(큐 full) 시 warn 로그 — PgInboxPollingWorker(Polling Worker) 가 fallback 처리.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InboxReadyEventHandler {

    private final PgInboxChannel channel;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handle(PgInboxReadyEvent event) {
        // offerNow — Kafka consumer thread(smoke trace 활성) 시점의 OTel Context + MDC snapshot 을 캡처한다.
        boolean offered = channel.offerNow(event.getInboxId());
        if (!offered) {
            LogFmt.warn(log, LogDomain.PG_INBOX, EventType.PG_INBOX_CHANNEL_OVERFLOW,
                    () -> "inboxId=" + event.getInboxId() + " — PgInboxPollingWorker(Polling Worker)가 처리 예정");
        }
    }
}
