package com.hyoguoo.paymentplatform.pg.listener;

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
 * <p>ADR-04 대칭: payment-service 의 OutboxImmediateEventHandler 와 동격.
 *
 * <p>T1-18 교훈 반영: pg-service 는 신규 서비스이므로 기본 활성화가 자연스럽다.
 * @ConditionalOnProperty 를 의도적으로 부여하지 않는다.
 *
 * <p>동작:
 * <ol>
 *   <li>PgOutboxReadyEvent 수신 (AFTER_COMMIT — DB 커밋 이후 호출 불변).</li>
 *   <li>PgOutboxChannel.offer(outboxId) 호출.</li>
 *   <li>offer 실패(큐 full) 시 warn 로그 — PgOutboxPollingWorker(Polling Worker) 가 fallback 처리.</li>
 * </ol>
 *
 * <p>LogFmt 미사용: pg-service 는 별도 LogFmt 복제본을 갖지 않아 @Slf4j 평문 로깅.
 * TODO: T5-02 LogFmt 공통화 완결 단계에서 pg-service 전용 LogFmt 복제(또는 공통 모듈 분리) 적용.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxReadyEventHandler {

    private final PgOutboxChannel channel;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PgOutboxReadyEvent event) {
        boolean offered = channel.offer(event.getOutboxId());
        if (!offered) {
            log.warn("PgOutboxChannel 오버플로우 발생 outboxId={} — PgOutboxPollingWorker(Polling Worker)가 처리 예정",
                    event.getOutboxId());
        }
    }
}
