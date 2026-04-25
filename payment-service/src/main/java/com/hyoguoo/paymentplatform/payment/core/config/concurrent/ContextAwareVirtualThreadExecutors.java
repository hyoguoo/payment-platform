package com.hyoguoo.paymentplatform.payment.core.config.concurrent;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.opentelemetry.context.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VT executor 에 OTel Context + MDC 양쪽을 자동 전파하도록 이중 래핑하는 헬퍼.
 *
 * <p>(1) {@link Context#taskWrapping(ExecutorService)} — OTel Context ThreadLocal 전파.
 * (2) {@link ContextExecutorService#wrap} — Micrometer ContextRegistry(MDC 등) 전파.
 *
 * <p>두 ThreadLocal 은 서로 다른 storage 라 양쪽 적용 필수.
 * 누락 시 traceparent 회귀 가능 — T-I2 / T-J3 / T-J4 라운드 검증 사례 참조.
 */
public final class ContextAwareVirtualThreadExecutors {

    private ContextAwareVirtualThreadExecutors() {}

    public static ExecutorService newWrappedVirtualThreadExecutor() {
        ExecutorService raw = Executors.newVirtualThreadPerTaskExecutor();
        ExecutorService otelWrapped = Context.taskWrapping(raw);
        return ContextExecutorService.wrap(otelWrapped, ContextSnapshotFactory.builder().build());
    }
}
