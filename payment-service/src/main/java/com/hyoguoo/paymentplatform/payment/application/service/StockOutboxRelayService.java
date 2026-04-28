package com.hyoguoo.paymentplatform.payment.application.service;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockOutboxPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockOutboxRepository;
import com.hyoguoo.paymentplatform.payment.domain.StockOutbox;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * stock_outbox row 한 건을 Kafka 로 publish 하는 relay.
 *
 * <p>처리 순서: findById → processedAt != null 이면 skip → publish → markProcessed. publish 가
 * 실패하면 예외를 전파하고 row 는 그대로 두어 다음 회차에 재시도된다. Transactional 은 markProcessed
 * 의 UPDATE 가 요구한다.
 *
 * <p>traceparent 는 outboxRelayExecutor 의 OTel Context + MDC 복원과
 * {@code spring.kafka.template.observation-enabled=true} 가 함께 보장한다 — 직접 헤더에 손대지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockOutboxRelayService {

    private final StockOutboxRepository stockOutboxRepository;
    private final StockOutboxPublisherPort stockOutboxPublisherPort;
    private final LocalDateTimeProvider localDateTimeProvider;

    @Transactional
    public void relay(Long outboxId) {
        LocalDateTime now = localDateTimeProvider.now();

        Optional<StockOutbox> outboxOpt = stockOutboxRepository.findById(outboxId);
        if (outboxOpt.isEmpty()) {
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION,
                    () -> "StockOutboxRelayService: outbox row 없음 id=" + outboxId);
            return;
        }
        StockOutbox outbox = outboxOpt.get();

        if (outbox.getProcessedAt() != null) {
            LogFmt.debug(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SKIPPED,
                    () -> "StockOutboxRelayService: 이미 처리된 row id=" + outboxId);
            return;
        }

        stockOutboxPublisherPort.send(outbox.getTopic(), outbox.getKey(), outbox.getPayload());
        stockOutboxRepository.markProcessed(outboxId, now);

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SUCCESS_COMPLETION,
                () -> "StockOutboxRelayService: relay 완료 id=" + outboxId
                        + " topic=" + outbox.getTopic() + " key=" + outbox.getKey());
    }
}
