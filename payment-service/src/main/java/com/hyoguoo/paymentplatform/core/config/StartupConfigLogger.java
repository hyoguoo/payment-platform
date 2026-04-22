package com.hyoguoo.paymentplatform.core.config;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StartupConfigLogger {

    @Value("${spring.threads.virtual.enabled:false}")
    private boolean virtualThreadsEnabled;

    @Value("${scheduler.outbox-worker.parallel-enabled:false}")
    private boolean outboxWorkerParallelEnabled;

    @Value("${scheduler.outbox-worker.batch-size:10}")
    private int outboxWorkerBatchSize;

    @Value("${scheduler.outbox-worker.fixed-delay-ms:5000}")
    private long outboxWorkerFixedDelayMs;

    @EventListener(ApplicationReadyEvent.class)
    public void logStartupConfig() {
        LogFmt.info(log, LogDomain.GLOBAL, EventType.APP_STARTUP,
                () -> String.format(
                        "virtual-threads=%s outbox-parallel=%s outbox-batch-size=%d outbox-fixed-delay-ms=%d",
                        virtualThreadsEnabled,
                        outboxWorkerParallelEnabled,
                        outboxWorkerBatchSize,
                        outboxWorkerFixedDelayMs
                )
        );
    }
}
