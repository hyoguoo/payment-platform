package com.hyoguoo.paymentplatform.payment.scheduler;

import com.hyoguoo.paymentplatform.core.channel.PaymentConfirmChannel;
import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.service.OutboxRelayService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxImmediateWorker implements SmartLifecycle {

    private final PaymentConfirmChannel channel;
    private final OutboxRelayService outboxRelayService;

    @Value("${outbox.channel.worker-count:200}")
    private int workerCount;

    @Value("${outbox.channel.virtual-threads:true}")
    private boolean virtualThreads;

    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running = false;

    @Override
    public void start() {
        running = true;
        for (int i = 0; i < workerCount; i++) {
            Thread worker = createWorkerThread(i);
            workers.add(worker);
            worker.start();
        }
        LogFmt.info(log, LogDomain.PAYMENT, EventType.APP_STARTUP,
                () -> "OutboxImmediateWorker started: workerCount=" + workerCount
                        + ", virtualThreads=" + virtualThreads);
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public void stop(Runnable callback) {
        running = false;
        workers.forEach(Thread::interrupt);
        workers.forEach(worker -> {
            try {
                worker.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        workers.clear();
        callback.run();
        LogFmt.info(log, LogDomain.PAYMENT, EventType.APP_STARTUP,
                () -> "OutboxImmediateWorker stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private Thread createWorkerThread(int index) {
        Thread.Builder builder = virtualThreads
                ? Thread.ofVirtual().name("outbox-immediate-worker-" + index)
                : Thread.ofPlatform().name("outbox-immediate-worker-" + index);
        return builder.unstarted(this::workerLoop);
    }

    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String orderId = channel.take();
                outboxRelayService.relay(orderId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);
            }
        }
    }
}
