package com.hyoguoo.paymentplatform.pg.infrastructure.channel;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.LinkedBlockingQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * pg-service Transactional Outbox 인메모리 전달 채널.
 *
 * <p>ADR-04 대칭: payment-service 의 PaymentConfirmChannel 과 동격.
 * element 는 pg_outbox.id (Long) — partition key 는 외부 payload 속성이므로 id 기반이 안전.
 *
 * <p>capacity=1024 (payment-service 와 동일).
 * 큐 full 시 offer=false 반환 → OutboxReadyEventHandler 가 warn 로그 → Polling Worker 가 fallback 처리.
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

    private final LinkedBlockingQueue<Long> queue;
    private final MeterRegistry meterRegistry;
    private final int capacity;

    public PgOutboxChannel(
            @Value("${pg.outbox.channel.capacity:1024}") int capacity,
            MeterRegistry meterRegistry
    ) {
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.meterRegistry = meterRegistry;
        this.capacity = capacity;
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
     * pg_outbox.id를 큐에 비차단 삽입한다.
     *
     * @param outboxId pg_outbox PK
     * @return 삽입 성공 여부 (false 이면 큐 full — Polling Worker 가 fallback 처리)
     */
    public boolean offer(Long outboxId) {
        return queue.offer(outboxId);
    }

    /**
     * 큐에서 outboxId 를 차단 대기하여 가져온다 (PgOutboxImmediateWorker 전용).
     *
     * @return pg_outbox PK
     * @throws InterruptedException 스레드 인터럽트 시
     */
    public Long take() throws InterruptedException {
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
