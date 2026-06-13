package com.hyoguoo.paymentplatform.pg.infrastructure.scheduler;

import com.hyoguoo.paymentplatform.pg.infrastructure.channel.ImmediateJob;
import io.micrometer.context.ContextSnapshot;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Immediate 워커 공통 SmartLifecycle 골격 + 이중 scope 컨텍스트 복원 템플릿.
 *
 * <p>{@link PgInboxImmediateWorker} / {@link PgOutboxImmediateWorker} 가 상속해
 * 워커별 차이(채널 take, 실행자, 위임 처리, 로그 도메인/이벤트, 카운터, 워커 이름 prefix)만 구현한다.
 *
 * <p>추출 범위:
 * <ul>
 *   <li>SmartLifecycle 골격 — {@code start()}, {@code stop()}, {@code stop(Runnable)},
 *       {@code isRunning()}, {@code getPhase()} (Integer.MAX_VALUE - 100)</li>
 *   <li>공통 worker loop 뼈대 — 인터럽트 체크, 채널 take, 실행자 submit, 예외 처리</li>
 *   <li>이중 scope 컨텍스트 복원 — {@code runWithContext(J job)}: MDC snapshot → OTel Context 순 open,
 *       AutoCloseable 역순 close</li>
 * </ul>
 *
 * <p>실행자 생성({@code ContextAwareVirtualThreadExecutors.newWrappedVirtualThreadExecutor()})은
 * 이미 공통 헬퍼화돼 있으므로 각 워커 생성자에서 직접 호출한다(추출 대상 제외).
 *
 * <p>Polling 2종({@link PgInboxPollingWorker}/{@link PgOutboxPollingWorker})은 비대상
 * — {@code @Scheduled} 별종 + DB 기반 traceparent 복원이라 헬퍼 공유 금지.
 *
 * @param <J> 처리 단위 Job 타입 — {@link ImmediateJob} 구현체
 */
@Slf4j
public abstract class AbstractImmediateWorker<J extends ImmediateJob> implements SmartLifecycle {

    private static final int STOP_AWAIT_TIMEOUT_SECONDS = 10;

    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running = false;

    // ---- SmartLifecycle 골격 ----

    @Override
    public void start() {
        running = true;
        initExecutor();
        int count = workerCount();
        for (int i = 0; i < count; i++) {
            Thread worker = Thread.ofVirtual()
                    .name(workerNamePrefix() + i)
                    .unstarted(this::workerLoop);
            workers.add(worker);
            worker.start();
        }
        logStarted(count);
    }

    @Override
    public void stop() {
        stop(() -> {
        });
    }

    @Override
    public void stop(Runnable callback) {
        running = false;
        workers.forEach(Thread::interrupt);
        workers.forEach(worker -> {
            try {
                worker.join(STOP_AWAIT_TIMEOUT_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        workers.clear();
        shutdownExecutor();
        callback.run();
        logStopped();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 채널보다 나중에 stop되어 in-flight 항목이 drain될 수 있도록 높은 phase 값을 사용한다.
     *
     * @return {@code Integer.MAX_VALUE - 100}
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    // ---- worker loop + 이중 scope 복원 ----

    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                J job = takeJob();
                executor().submit(() -> runWithContext(job));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                logLoopError(e);
            }
        }
    }

    /**
     * offer 시점의 MDC snapshot(Micrometer) → OTel Context 순으로 scope 를 열어 처리를 위임한다.
     * try-with-resources 이중 scope — 역순 close 로 traceparent 가 처리 내부까지 정확히 전파된다.
     */
    private void runWithContext(J job) {
        try (
                ContextSnapshot.Scope mdcScope = job.snapshot().setThreadLocals();
                Scope otelScope = job.otelContext().makeCurrent()
        ) {
            handle(job);
        }
    }

    // ---- 서브클래스 위임 ----

    /**
     * 실행자({@link ExecutorService})를 초기화한다.
     * {@code start()} 에서 1회 호출 — {@code ContextAwareVirtualThreadExecutors.newWrappedVirtualThreadExecutor()} 로 생성한다.
     */
    protected abstract void initExecutor();

    /**
     * 현재 활성 실행자를 반환한다.
     */
    protected abstract ExecutorService executor();

    /**
     * 실행자를 graceful shutdown 한다. {@link #stop(Runnable)} 에서 호출.
     */
    protected abstract void shutdownExecutor();

    /**
     * 워커 VT 스레드 이름 prefix. 예: {@code "pg-inbox-immediate-worker-"}.
     */
    protected abstract String workerNamePrefix();

    /**
     * 기동할 워커 스레드 수.
     */
    protected abstract int workerCount();

    /**
     * 채널에서 다음 Job 을 blocking 으로 꺼낸다.
     *
     * @throws InterruptedException 스레드 인터럽트 시
     */
    protected abstract J takeJob() throws InterruptedException;

    /**
     * Job 을 실제 처리한다 (OTel/MDC context 복원 후 호출됨).
     */
    protected abstract void handle(J job);

    /**
     * 워커 기동 완료 로그를 출력한다.
     */
    protected abstract void logStarted(int count);

    /**
     * 워커 종료 완료 로그를 출력한다.
     */
    protected abstract void logStopped();

    /**
     * workerLoop 에서 {@link RuntimeException} 포획 시 ERROR 로그를 출력한다.
     */
    protected abstract void logLoopError(RuntimeException e);

    // ---- 실행자 graceful shutdown 공통 유틸 ----

    /**
     * ExecutorService 를 graceful shutdown({@value #STOP_AWAIT_TIMEOUT_SECONDS}s awaitTermination 후 shutdownNow)한다.
     * 각 워커의 {@link #shutdownExecutor()} 구현에서 호출한다.
     */
    protected static void awaitShutdown(ExecutorService executorService) {
        if (executorService == null) {
            return;
        }
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(STOP_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
