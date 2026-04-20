package com.hyoguoo.paymentplatform.core.channel;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PaymentConfirmChannel {

    private static final double NEAR_FULL_THRESHOLD = 0.1;

    private final LinkedBlockingQueue<String> queue;
    private final MeterRegistry meterRegistry;
    private final int capacity;

    public PaymentConfirmChannel(
            @Value("${outbox.channel.capacity:2000}") int capacity,
            MeterRegistry meterRegistry
    ) {
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.meterRegistry = meterRegistry;
        this.capacity = capacity;
    }

    @PostConstruct
    public void registerMetrics() {
        Gauge.builder("payment_confirm_channel_queue_size", queue, LinkedBlockingQueue::size)
                .description("현재 채널 큐에 대기 중인 항목 수")
                .register(meterRegistry);
        Gauge.builder("payment_confirm_channel_remaining_capacity", queue, LinkedBlockingQueue::remainingCapacity)
                .description("채널 큐의 잔여 용량")
                .register(meterRegistry);
    }

    public boolean offer(String orderId) {
        return queue.offer(orderId);
    }

    public String take() throws InterruptedException {
        return queue.take();
    }

    public boolean isNearFull() {
        return queue.remainingCapacity() <= (int) (capacity * NEAR_FULL_THRESHOLD);
    }
}
