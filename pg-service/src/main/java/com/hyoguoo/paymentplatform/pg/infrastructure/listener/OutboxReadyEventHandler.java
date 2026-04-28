package com.hyoguoo.paymentplatform.pg.infrastructure.listener;

import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.event.PgOutboxReadyEvent;
import com.hyoguoo.paymentplatform.pg.infrastructure.channel.PgOutboxChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * pg_outbox row DB 커밋 이후 PgOutboxChannel 에 outboxId 를 전달하는 AFTER_COMMIT 리스너.
 *
 * <p>payment-service 의 OutboxImmediateEventHandler 와 대칭 위치.
 *
 * <p>pg-service 는 신규 서비스이므로 기본 활성화가 자연스럽다 — @ConditionalOnProperty 는 의도적으로 부여하지 않는다.
 *
 * <p>동작:
 * <ol>
 *   <li>PgOutboxReadyEvent 수신 (AFTER_COMMIT — DB 커밋 이후 호출 불변).</li>
 *   <li>PgOutboxChannel.offer(outboxId) 호출.</li>
 *   <li>offer 실패(큐 full) 시 warn 로그 — PgOutboxPollingWorker(Polling Worker) 가 fallback 처리.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxReadyEventHandler {

    private final PgOutboxChannel channel;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handle(PgOutboxReadyEvent event) {
        // offerNow — Kafka consumer thread(smoke trace 활성) 시점의 OTel Context + MDC snapshot 을 캡처한다.
        boolean offered = channel.offerNow(event.getOutboxId());
        if (!offered) {
            LogFmt.warn(log, LogDomain.PG_OUTBOX, EventType.PG_OUTBOX_CHANNEL_OVERFLOW,
                    () -> "outboxId=" + event.getOutboxId() + " — PgOutboxPollingWorker(Polling Worker)가 처리 예정");
        }
    }
}
