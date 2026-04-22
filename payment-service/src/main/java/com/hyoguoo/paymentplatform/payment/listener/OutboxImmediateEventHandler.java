package com.hyoguoo.paymentplatform.payment.listener;

import com.hyoguoo.paymentplatform.payment.application.service.OutboxRelayService;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentConfirmEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 결제 confirm TX 커밋 직후 outbox relay를 트리거하는 리스너.
 *
 * <p>실행 모델: {@code AFTER_COMMIT} + {@code @Async("outboxRelayExecutor")}
 * 조합이다. 전자는 TX 커밋 이후에만 Kafka 발행이 일어나도록 보장하고,
 * 후자는 호출 스레드(Tomcat 워커)를 즉시 풀어 relay를 VT에서 수행하게 한다.
 *
 * <p>크래시·리스너 스킵은 {@code OutboxWorker}(fixed-delay 폴링)가 PENDING을
 * 재픽업하는 fallback으로 회복된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "payment.monolith.confirm.enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class OutboxImmediateEventHandler {

    private final OutboxRelayService outboxRelayService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("outboxRelayExecutor")
    public void handle(PaymentConfirmEvent event) {
        outboxRelayService.relay(event.getOrderId());
    }
}
