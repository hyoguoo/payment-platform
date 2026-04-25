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
 * stock_outbox relay 서비스.
 * T-J1: stock commit/restore 이벤트 Transactional Outbox 패턴.
 *
 * <p>ADR-19 복제(b): pg-service {@code PgOutboxRelayService} 구조를 독립 복제.
 *
 * <p>relay(id) 처리 순서:
 * <ol>
 *   <li>findById — row 없으면 warn + return (no-op).</li>
 *   <li>processedAt != null → 이미 발행된 row, skip (중복 방지).</li>
 *   <li>stockOutboxPublisherPort.send(topic, key, payload) — 실패 시 예외 전파(row 미갱신).</li>
 *   <li>성공 시 markProcessed(id, now).</li>
 * </ol>
 *
 * <p>traceparent 전파:
 * outboxRelayExecutor(@Async, T-I2 이중 래핑)이 submit 시점 OTel Context + MDC를
 * VT에서 자동 복원한다. spring.kafka.template.observation-enabled=true가
 * 복원된 context에서 traceparent를 자동 주입하므로 traceparent 회귀 없음.
 *
 * <p>@Transactional: markProcessed(@Modifying UPDATE)가 TX를 요구한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockOutboxRelayService {

    private final StockOutboxRepository stockOutboxRepository;
    private final StockOutboxPublisherPort stockOutboxPublisherPort;
    /**
     * K5: 시간 소스 주입 — LocalDateTime.now() 직접 호출 제거.
     * 테스트에서 fixed clock 주입 → relay processedAt 결정성 보장.
     */
    private final LocalDateTimeProvider localDateTimeProvider;

    @Transactional
    public void relay(Long outboxId) {
        // K5: LocalDateTime.now() 직접 호출 제거 → localDateTimeProvider.now() 사용
        LocalDateTime now = localDateTimeProvider.now();

        Optional<StockOutbox> outboxOpt = stockOutboxRepository.findById(outboxId);
        if (outboxOpt.isEmpty()) {
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION,
                    () -> "StockOutboxRelayService: outbox row 없음 id=" + outboxId);
            return;
        }
        StockOutbox outbox = outboxOpt.get();

        // 이미 발행된 row — 중복 방지
        if (outbox.getProcessedAt() != null) {
            LogFmt.debug(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SKIPPED,
                    () -> "StockOutboxRelayService: 이미 처리된 row id=" + outboxId);
            return;
        }

        // publish — 실패 시 예외 전파(row 미갱신, 재시도 가능)
        // Kafka 헤더는 spring.kafka.template.observation-enabled=true 가 publish 시점의
        // 현재 span 에서 traceparent 를 자동 주입한다. outbox row 의 headers_json 은
        // 향후 확장(예: attempt 카운터)을 위해 예약 필드이며 현 시점에는 사용하지 않는다.
        stockOutboxPublisherPort.send(outbox.getTopic(), outbox.getKey(), outbox.getPayload());

        // 성공 시 processed_at 갱신
        stockOutboxRepository.markProcessed(outboxId, now);

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SUCCESS_COMPLETION,
                () -> "StockOutboxRelayService: relay 완료 id=" + outboxId
                        + " topic=" + outbox.getTopic() + " key=" + outbox.getKey());
    }
}
