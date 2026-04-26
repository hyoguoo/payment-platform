package com.hyoguoo.paymentplatform.payment.infrastructure.listener;

import com.hyoguoo.paymentplatform.payment.application.event.StockOutboxReadyEvent;
import com.hyoguoo.paymentplatform.payment.application.service.StockOutboxRelayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * stock_outbox row DB 커밋 직후 relay 를 트리거하는 AFTER_COMMIT 리스너.
 *
 * <p>실행 모델: {@code AFTER_COMMIT} + {@code @Async("outboxRelayExecutor")} 조합.
 * <ul>
 *   <li>AFTER_COMMIT — DB TX 커밋 이후에만 Kafka 발행이 일어나도록 보장.</li>
 *   <li>@Async("outboxRelayExecutor") — OTel Context + MDC 이중 래핑이 적용되어 submit 시점의
 *       trace context 가 VT 에서 정확히 복원되므로 traceparent 가 끊기지 않는다.</li>
 * </ul>
 *
 * <p>payment.commands.confirm 발행의 {@code OutboxImmediateEventHandler} 와 동일 패턴이다.
 *
 * <p>fallbackExecution=true — Spring TX 없이 publishEvent 호출될 때도 AFTER_COMMIT 처리.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockOutboxImmediateEventHandler {

    private final StockOutboxRelayService stockOutboxRelayService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Async("outboxRelayExecutor")
    public void handle(StockOutboxReadyEvent event) {
        stockOutboxRelayService.relay(event.outboxId());
    }
}
