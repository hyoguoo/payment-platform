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
 * pg-service Transactional Inbox 인메모리 전달 채널.
 *
 * <p>{@link PgOutboxChannel} 1:1 거울 위치 (발행 측 ↔ 수신 측).
 * element 는 {@link InboxJob} — pg_inbox.id(Long) + offer 시점의 OTel Context + MDC snapshot.
 *
 * <p>capacity=1024 (PgOutboxChannel 과 동일).
 * 큐 full 시 offer=false 반환 → InboxReadyEventHandler 가 warn 로그 → Polling Worker 가 fallback 처리.
 *
 * <p>offer 시점(Kafka consumer thread)에서 current OTel Context + ContextSnapshot 을 캡처하고
 * worker VT thread 에서 처리 직전에 restore 한다.
 */
@Slf4j
@Component
public class PgInboxChannel {

    /**
     * isNearFull 판정에 사용하는 임계치 (80% — 잔여 용량 20% 이하).
     * 정수 연산으로 표현: remainingCapacity * 5 < capacity 이면 nearFull.
     * (부동소수점 곱셈 오류 회피)
     */
    private static final int NEAR_FULL_DIVISOR = 5; // 1 / 0.2 = 5

    private final LinkedBlockingQueue<InboxJob> queue;
    private final MeterRegistry meterRegistry;
    private final int capacity;
    private final ContextSnapshotFactory snapshotFactory;

    public PgInboxChannel(
            @Value("${pg.inbox.channel.capacity:1024}") int capacity,
            MeterRegistry meterRegistry
    ) {
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.meterRegistry = meterRegistry;
        this.capacity = capacity;
        this.snapshotFactory = ContextSnapshotFactory.builder().build();
    }

    @PostConstruct
    public void registerMetrics() {
        Gauge.builder("pg_inbox_channel_queue_size", queue, LinkedBlockingQueue::size)
                .description("현재 pg_inbox 채널 큐에 대기 중인 항목 수")
                .register(meterRegistry);
        Gauge.builder("pg_inbox_channel_remaining_capacity", queue, LinkedBlockingQueue::remainingCapacity)
                .description("pg_inbox 채널 큐의 잔여 용량")
                .register(meterRegistry);
    }

    /**
     * pg_inbox.id 를 큐에 비차단 삽입한다. 호출 스레드의 OTel Context + MDC snapshot 을 캡처하여 동봉한다.
     *
     * <p>offer 시점(Kafka consumer thread)에서 current OTel Context + ContextSnapshot 을 캡처해 두면,
     * worker VT thread 가 처리 직전에 restore 해 traceparent 가 전파된다.
     *
     * @param inboxId pg_inbox PK
     * @return 삽입 성공 여부 (false 이면 큐 full — Polling Worker 가 fallback 처리)
     */
    public boolean offerNow(Long inboxId) {
        Context otelContext = Context.current();
        ContextSnapshot snapshot = snapshotFactory.captureAll();
        return queue.offer(new InboxJob(inboxId, otelContext, snapshot));
    }

    /**
     * 큐에서 {@link InboxJob} 을 차단 대기하여 가져온다 (PgInboxImmediateWorker 전용).
     *
     * <p>반환된 job 의 otelContext + snapshot 을 처리 직전에 restore 하여 context 를 복원해야 한다.
     *
     * @return InboxJob (inboxId + offer 시점 OTel Context + MDC snapshot)
     * @throws InterruptedException 스레드 인터럽트 시
     */
    public InboxJob take() throws InterruptedException {
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
     * 큐가 임계치(80%) 이상 찼는지 반환한다.
     *
     * @return true 이면 잔여 용량 ≤ 20%
     */
    public boolean isNearFull() {
        // 잔여 용량이 20% 이하(= 80% 이상 소진)인 경우 nearFull
        // 정수 연산: remainingCapacity * 5 < capacity
        return queue.remainingCapacity() * NEAR_FULL_DIVISOR < capacity;
    }
}
