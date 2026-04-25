package com.hyoguoo.paymentplatform.payment.listener;

import com.hyoguoo.paymentplatform.payment.application.event.StockOutboxReadyEvent;
import com.hyoguoo.paymentplatform.payment.application.service.StockOutboxRelayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * stock_outbox row DB 커밋 직후 relay를 트리거하는 AFTER_COMMIT 리스너.
 * T-J1: stock commit/restore 이벤트 Transactional Outbox 패턴.
 *
 * <p>실행 모델: {@code AFTER_COMMIT} + {@code @Async("outboxRelayExecutor")} 조합.
 * <ul>
 *   <li>AFTER_COMMIT — DB TX 커밋 이후에만 Kafka 발행이 일어나도록 보장.</li>
 *   <li>@Async("outboxRelayExecutor") — T-I2 이중 래핑(OTel Context + MDC) 적용.
 *       submit 시점 OTel Context와 MDC를 VT에서 정확히 복원 → traceparent 회귀 없음.</li>
 * </ul>
 *
 * <p>ADR-04 대칭: payment.commands.confirm 발행의 {@code OutboxImmediateEventHandler}와
 * 동일한 검증된 패턴.
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
