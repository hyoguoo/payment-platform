package com.hyoguoo.paymentplatform.payment.listener;

import com.hyoguoo.paymentplatform.core.channel.PaymentConfirmChannel;
import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentConfirmEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "payment.monolith.confirm.enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class OutboxImmediateEventHandler {

    private final PaymentConfirmChannel channel;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PaymentConfirmEvent event) {
        boolean offered = channel.offer(event.getOrderId());
        if (!offered) {
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION,
                    () -> "PaymentConfirmChannel 오버플로우 발생 — OutboxWorker(polling)가 처리 예정");
        }
    }
}
