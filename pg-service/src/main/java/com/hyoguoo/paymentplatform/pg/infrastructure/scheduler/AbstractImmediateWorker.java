package com.hyoguoo.paymentplatform.pg.infrastructure.scheduler;

import com.hyoguoo.paymentplatform.pg.infrastructure.channel.ImmediateJob;
import io.micrometer.context.ContextSnapshot;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Immediate 워커 공통 SmartLifecycle 골격 + 이중 scope 컨텍스트 복원 템플릿.
 *
 * <p>{@link PgInboxImmediateWorker} / {@link PgOutboxImmediateWorker} 가 상속해
 * 워커별 차이(채널 poll, 위임 처리, 로그 도메인/이벤트, 카운터, 워커 이름 prefix)만 구현한다.
 *
 * <p>워커 스레드가 꺼낸 작업을 직접 처리한다. 그래서 worker-count 가 동시 처리 상한이다.
 * 작업마다 새 스레드에 넘기면 상한이 사라져, 벤더나 DB 가 느려질 때 동시 호출과 연결 대기가 끝없이 늘어난다.
 *
 * <p>종료: {@code running=false} 로 새 작업을 그만 꺼내게 하고, 처리 중인 작업은 최대
 * {@value #STOP_AWAIT_TIMEOUT_SECONDS}초 기다린다. 그래도 남은 워커만 인터럽트한다. 끊긴 작업은 폴링 워커의
 * 정체 판정 회수 경로가 다시 처리한다.
 *
 * <p>Polling 2종({@link PgInboxPollingWorker}/{@link PgOutboxPollingWorker})은 비대상
 * — {@code @Scheduled} 별종 + DB 기반 traceparent 복원이라 헬퍼 공유 금지.
 *
 * @param <J> 처리 단위 Job 타입 — {@link ImmediateJob} 구현체
 */
@Slf4j
public abstract class AbstractImmediateWorker<J extends ImmediateJob> implements SmartLifecycle {

    private static final int STOP_AWAIT_TIMEOUT_SECONDS = 10;
    private static final long POLL_TIMEOUT_MILLIS = 200L;
    private static final long INTERRUPT_JOIN_MILLIS = 1_000L;

    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running = false;

    // ---- SmartLifecycle 골격 ----

    @Override
    public void start() {
        running = true;
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STOP_AWAIT_TIMEOUT_SECONDS);
        for (Thread worker : workers) {
            joinUntil(worker, deadline);
        }
        // 남은 워커를 모두 인터럽트한 뒤 함께 기다린다. 하나씩 기다리면 종료 시간이 워커 수만큼 늘어난다.
        List<Thread> remaining = workers.stream().filter(Thread::isAlive).toList();
        remaining.forEach(Thread::interrupt);
        long interruptDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(INTERRUPT_JOIN_MILLIS);
        for (Thread worker : remaining) {
            joinUntil(worker, interruptDeadline);
        }
        workers.clear();
        callback.run();
        logStopped();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 높은 phase 값으로 늦게 stop 한다. 채널은 Lifecycle 빈이 아니고 Kafka 리스너 레지스트리와 phase 가 같아
     * 종료 순서는 정해져 있지 않다. stop 은 처리 중인 작업을 기다릴 뿐 채널에 남은 작업을 비우지는 않는다 —
     * 남은 작업은 폴링 워커가 DB 상태로 다시 찾는다.
     *
     * @return {@code Integer.MAX_VALUE - 100}
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    // ---- worker loop + 이중 scope 복원 ----

    private void workerLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                J job = pollJob(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                if (job != null) {
                    runWithContext(job);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException | Error e) {
                // 워커가 죽으면 동시 처리 몫이 영구히 줄어든다. Error 도 기록하고 다음 작업으로 넘어간다.
                logLoopError(e);
            }
        }
    }

    /**
     * offer 시점의 MDC snapshot(Micrometer) → OTel Context 순으로 scope 를 열어 처리를 위임한다.
     * try-with-resources 이중 scope — 역순 close 로 traceparent 가 처리 내부까지 정확히 전파되고,
     * 처리 뒤 워커 스레드의 컨텍스트는 원래대로 돌아온다.
     */
    private void runWithContext(J job) {
        try (
                ContextSnapshot.Scope mdcScope = job.snapshot().setThreadLocals();
                Scope otelScope = job.otelContext().makeCurrent()
        ) {
            handle(job);
        }
    }

    private static void joinUntil(Thread worker, long deadlineNanos) {
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remainingMillis > 0) {
            joinQuietly(worker, remainingMillis);
        }
    }

    private static void joinQuietly(Thread worker, long millis) {
        try {
            worker.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 서브클래스 위임 ----

    /**
     * 워커 VT 스레드 이름 prefix. 예: {@code "pg-inbox-immediate-worker-"}.
     */
    protected abstract String workerNamePrefix();

    /**
     * 기동할 워커 스레드 수. 동시 처리 상한이다.
     */
    protected abstract int workerCount();

    /**
     * 채널에서 다음 Job 을 최대 {@code timeout} 동안 기다려 꺼낸다. 없으면 {@code null}.
     *
     * @throws InterruptedException 스레드 인터럽트 시
     */
    protected abstract J pollJob(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Job 을 실제 처리한다 (OTel/MDC context 복원 후 워커 스레드에서 호출됨).
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
     * workerLoop 에서 {@link RuntimeException} 또는 {@link Error} 포획 시 ERROR 로그를 출력한다.
     */
    protected abstract void logLoopError(Throwable e);
}
