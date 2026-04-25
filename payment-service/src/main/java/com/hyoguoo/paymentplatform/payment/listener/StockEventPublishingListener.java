package com.hyoguoo.paymentplatform.payment.listener;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.dto.StockRestoreEventPayload;
import com.hyoguoo.paymentplatform.payment.application.event.StockCommitRequestedEvent;
import com.hyoguoo.paymentplatform.payment.application.event.StockRestoreRequestedEvent;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCommitEventPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRestoreEventPublisherPort;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.core.instrument.Counter;
import io.opentelemetry.context.Scope;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * TX commit 이후 stock Kafka 발행을 담당하는 리스너.
 * ADR-04: TX 내부는 ApplicationEvent 발행만 — 실제 Kafka publish는 AFTER_COMMIT에서 실행.
 *
 * <p>DB TX 블로킹 방지 원칙:
 * <ul>
 *   <li>Kafka broker 지연이 DB TX 홀딩 시간에 영향을 주지 않는다.</li>
 *   <li>commit 성공 후 Kafka 발행 실패 시 TX는 이미 완료 — 복구 정책은 별도 태스크(T-E/F).</li>
 * </ul>
 *
 * <p>실패 처리: LogFmt.error + {@code stock.kafka.publish.fail.total} counter 증가 + 예외 삼킴.
 * TX는 이미 commit이므로 rollback 불가. Kafka broker 장시간 중단 시 이 counter + ERROR 로그로 감시.
 * Phase 4 이후 stock outbox 이관 예정 (TODOS.md 참고).
 */
@Slf4j
@Component
public class StockEventPublishingListener {

    /** Micrometer counter 이름 — Prometheus에서 {@code stock_kafka_publish_fail_total} 으로 노출된다. */
    public static final String METRIC_NAME = "stock.kafka.publish.fail.total";
    public static final String TAG_EVENT = "event";
    public static final String TAG_EVENT_COMMIT = "commit";
    public static final String TAG_EVENT_RESTORE = "restore";

    private final StockCommitEventPublisherPort stockCommitEventPublisherPort;
    private final StockRestoreEventPublisherPort stockRestoreEventPublisherPort;
    private final Counter commitFailCounter;
    private final Counter restoreFailCounter;

    public StockEventPublishingListener(
            StockCommitEventPublisherPort stockCommitEventPublisherPort,
            StockRestoreEventPublisherPort stockRestoreEventPublisherPort,
            MeterRegistry meterRegistry) {
        this.stockCommitEventPublisherPort = stockCommitEventPublisherPort;
        this.stockRestoreEventPublisherPort = stockRestoreEventPublisherPort;
        this.commitFailCounter = Counter.builder(METRIC_NAME)
                .description("stock Kafka 발행 실패 누적 건수 (AFTER_COMMIT, TX 이미 commit)")
                .tag(TAG_EVENT, TAG_EVENT_COMMIT)
                .register(meterRegistry);
        this.restoreFailCounter = Counter.builder(METRIC_NAME)
                .description("stock Kafka 발행 실패 누적 건수 (AFTER_COMMIT, TX 이미 commit)")
                .tag(TAG_EVENT, TAG_EVENT_RESTORE)
                .register(meterRegistry);
    }

    /**
     * AFTER_COMMIT: stock.events.commit 실제 Kafka 발행.
     * TX commit 성공 후에만 실행 — 발행 실패는 TX 영향 없음.
     *
     * <p>T-I4: AFTER_COMMIT 시점에 KafkaListener observation이 이미 닫혀 active span이 소실된다.
     * event에 포함된 ContextSnapshot으로 producer 측 MDC context를 복원하여
     * KafkaTemplate.send()가 올바른 traceparent를 헤더에 주입하도록 한다.
     *
     * <p>T-I7: ContextSnapshot(captureAll)은 Micrometer ContextRegistry(MDC)만 복원한다.
     * OTel Context 는 별도 ThreadLocal이므로 event.otelContext().makeCurrent() 로 명시 활성화.
     * try-with-resources 이중 중첩 — 종료 순서는 otelScope → mdcScope (LIFO) 로 자동 복원.
     *
     * <p>T-I8: @Async("outboxRelayExecutor") 추가 — T-I2 의 이중 래핑(OTel Context.taskWrapping
     * + ContextExecutorService.wrap)이 submit 시점 OTel Context 와 MDC 를 VT 에서 자동 복원한다.
     * 기존 try-with-resources(T-I4 + T-I7)는 이중 보호로 유지.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("outboxRelayExecutor")
    public void onStockCommitRequested(StockCommitRequestedEvent event) {
        try (
                ContextSnapshot.Scope mdcScope = event.contextSnapshot().setThreadLocals();
                Scope otelScope = event.otelContext().makeCurrent()
        ) {
            stockCommitEventPublisherPort.publish(
                    event.productId(),
                    event.quantity(),
                    event.idempotencyKey()
            );
            LogFmt.debug(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_SUCCESS,
                    () -> "stockCommit orderId=" + event.orderId()
                            + " productId=" + event.productId()
                            + " qty=" + event.quantity());
        } catch (RuntimeException e) {
            commitFailCounter.increment();
            LogFmt.error(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_FAIL,
                    () -> "stockCommit 발행 실패 — orderId=" + event.orderId()
                            + " productId=" + event.productId()
                            + " cause=" + e.getMessage()
                            + " action=SWALLOW(TX already committed)"
                            + " metric=stock.kafka.publish.fail.total[event=commit]++");
        }
    }

    /**
     * AFTER_COMMIT: stock.events.restore 실제 Kafka 발행.
     * TX commit 성공 후에만 실행 — 발행 실패는 TX 영향 없음.
     *
     * <p>T-I4: AFTER_COMMIT 시점에 KafkaListener observation이 이미 닫혀 active span이 소실된다.
     * event에 포함된 ContextSnapshot으로 producer 측 MDC context를 복원하여
     * KafkaTemplate.send()가 올바른 traceparent를 헤더에 주입하도록 한다.
     *
     * <p>T-I7: ContextSnapshot(captureAll)은 Micrometer ContextRegistry(MDC)만 복원한다.
     * OTel Context 는 별도 ThreadLocal이므로 event.otelContext().makeCurrent() 로 명시 활성화.
     * try-with-resources 이중 중첩 — 종료 순서는 otelScope → mdcScope (LIFO) 로 자동 복원.
     *
     * <p>T-I8: @Async("outboxRelayExecutor") 추가 — T-I2 의 이중 래핑이 submit 시점
     * OTel Context 와 MDC 를 VT 에서 자동 복원한다. 기존 try-with-resources(T-I4 + T-I7) 이중 보호 유지.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("outboxRelayExecutor")
    public void onStockRestoreRequested(StockRestoreRequestedEvent event) {
        StockRestoreEventPayload payload = buildRestorePayload(event);
        try (
                ContextSnapshot.Scope mdcScope = event.contextSnapshot().setThreadLocals();
                Scope otelScope = event.otelContext().makeCurrent()
        ) {
            stockRestoreEventPublisherPort.publishPayload(payload);
            LogFmt.debug(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_SUCCESS,
                    () -> "stockRestore orderId=" + event.orderId()
                            + " productId=" + event.productId()
                            + " qty=" + event.quantity());
        } catch (RuntimeException e) {
            restoreFailCounter.increment();
            LogFmt.error(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_FAIL,
                    () -> "stockRestore 발행 실패 — orderId=" + event.orderId()
                            + " productId=" + event.productId()
                            + " cause=" + e.getMessage()
                            + " action=SWALLOW(TX already committed)"
                            + " metric=stock.kafka.publish.fail.total[event=restore]++");
        }
    }

    private static StockRestoreEventPayload buildRestorePayload(StockRestoreRequestedEvent event) {
        UUID eventUUID = UUID.fromString(event.eventUUID());
        return new StockRestoreEventPayload(
                eventUUID,
                event.orderId(),
                event.productId(),
                event.quantity()
        );
    }
}
