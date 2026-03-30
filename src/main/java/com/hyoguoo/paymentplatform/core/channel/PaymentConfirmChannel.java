package com.hyoguoo.paymentplatform.core.channel;

import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PaymentConfirmChannel {

    private final LinkedBlockingQueue<String> queue;

    public PaymentConfirmChannel(@Value("${outbox.channel.capacity:2000}") int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    public boolean offer(String orderId) {
        return queue.offer(orderId);
    }

    public String take() throws InterruptedException {
        return queue.take();
    }
}
