package com.hyoguoo.paymentplatform.pg.infrastructure.channel;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.context.Context;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.LinkedBlockingQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * pg-service Transactional Outbox 인메모리 전달 채널.
 *
 * <p>ADR-04 대칭: payment-service 의 PaymentConfirmChannel 과 동격.
 * element 는 {@link OutboxJob} — pg_outbox.id(Long) + offer 시점의 OTel Context + MDC snapshot.
 *
 * <p>capacity=1024 (payment-service 와 동일).
 * 큐 full 시 offer=false 반환 → OutboxReadyEventHandler 가 warn 로그 → Polling Worker 가 fallback 처리.
 *
 * <p>T-J4: offer 시점(Kafka consumer thread — smoke trace 활성)에서 context 를 캡처하여
 * worker VT thread 에서 relay 직전에 restore — payment.events.confirmed traceparent 회귀 근본 해소.
 */
@Slf4j
@Component
public class PgOutboxChannel {

    /**
     * isNearFull 판정에 사용하는 임계치 (80% — 잔여 용량 20% 이하).
     * 정수 연산으로 표현: remainingCapacity * 5 < capacity 이면 nearFull.
     * (부동소수점 곱셈 오류 회피)
     */
    private static final int NEAR_FULL_DIVISOR = 5; // 1 / 0.2 = 5

    private final LinkedBlockingQueue<OutboxJob> queue;
    private final MeterRegistry meterRegistry;
    private final int capacity;
    private final ContextSnapshotFactory snapshotFactory;

    public PgOutboxChannel(
            @Value("${pg.outbox.channel.capacity:1024}") int capacity,
            MeterRegistry meterRegistry
    ) {
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.meterRegistry = meterRegistry;
        this.capacity = capacity;
        this.snapshotFactory = ContextSnapshotFactory.builder().build();
    }

    @PostConstruct
    public void registerMetrics() {
        Gauge.builder("pg_outbox_channel_queue_size", queue, LinkedBlockingQueue::size)
                .description("현재 pg_outbox 채널 큐에 대기 중인 항목 수")
                .register(meterRegistry);
        Gauge.builder("pg_outbox_channel_remaining_capacity", queue, LinkedBlockingQueue::remainingCapacity)
                .description("pg_outbox 채널 큐의 잔여 용량")
                .register(meterRegistry);
    }

    /**
     * pg_outbox.id 를 큐에 비차단 삽입한다. 호출 스레드의 OTel Context + MDC snapshot 을 캡처하여 동봉한다.
     *
     * <p>T-J4: offer 시점(Kafka consumer thread)에서 current OTel Context + ContextSnapshot 을 캡처한다.
     * 이 context 는 worker VT thread 가 relay 직전에 restore 하여 smoke traceparent 를 Kafka 헤더에 전파한다.
     *
     * @param outboxId pg_outbox PK
     * @return 삽입 성공 여부 (false 이면 큐 full — Polling Worker 가 fallback 처리)
     */
    public boolean offerNow(Long outboxId) {
        Context otelContext = Context.current();
        ContextSnapshot snapshot = snapshotFactory.captureAll();
        return queue.offer(new OutboxJob(outboxId, otelContext, snapshot));
    }

    /**
     * pg_outbox.id 를 큐에 비차단 삽입한다 (하위 호환 API).
     *
     * <p>내부적으로 {@link #offerNow(Long)} 에 위임하여 OTel Context + MDC snapshot 을 동봉한다.
     *
     * @param outboxId pg_outbox PK
     * @return 삽입 성공 여부 (false 이면 큐 full — Polling Worker 가 fallback 처리)
     */
    public boolean offer(Long outboxId) {
        return offerNow(outboxId);
    }

    /**
     * 큐에서 {@link OutboxJob} 을 차단 대기하여 가져온다 (PgOutboxImmediateWorker 전용).
     *
     * <p>반환된 job 의 otelContext + snapshot 을 relay 직전에 restore 하여 context 를 복원해야 한다.
     *
     * @return OutboxJob (outboxId + offer 시점 OTel Context + MDC snapshot)
     * @throws InterruptedException 스레드 인터럽트 시
     */
    public OutboxJob take() throws InterruptedException {
        return queue.take();
    }

    /**
     * 현재 큐 크기 (관측용).
     *
     * @return 큐에 대기 중인 항목 수
     */
    public int size() {
        return queue.size();
    }

    /**
     * 큐가 임계치(80%) 이상 찼는지 반환한다 (메트릭 게이지 대상).
     *
     * @return true 이면 잔여 용량 ≤ 20%
     */
    public boolean isNearFull() {
        // 잔여 용량이 20% 이하(= 80% 이상 소진)인 경우 nearFull
        // 정수 연산: remainingCapacity * 5 < capacity
        return queue.remainingCapacity() * NEAR_FULL_DIVISOR < capacity;
    }
}
