package com.hyoguoo.paymentplatform.payment.core.test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 두 개 이상의 동작을 같은 시점에 풀어 동시에 실행시키는 범용 테스트 하네스.
 *
 * <p>{@code CountDownLatch}로 모든 스레드가 준비될 때까지 기다린 뒤 한 번에 풀어 실제 경합을
 * 재현한다({@code OutboxPublishFailureConcurrentClaimIntegrationTest} / {@code
 * PaymentCheckoutConcurrencyIntegrationTest}가 각자 인라인으로 쓰던 것과 같은 패턴). 재고 선차감
 * 이중 되돌리기 검증(16a~16c)이 공유하는 하네스라 상품 도메인에 종속된 코드를 두지 않는다.
 */
public final class ConcurrentActionRunner {

    private static final long AWAIT_TIMEOUT_SECONDS = 10L;

    private ConcurrentActionRunner() {
    }

    /**
     * 전달된 동작을 모두 같은 시점에 동시 실행하고, 전부 끝날 때까지 기다린다.
     *
     * @param actions 동시에 실행할 동작 (2개 이상)
     * @throws AssertionError 실행 중 하나라도 예외를 던지면, 첫 예외를 원인으로 담아 던진다 —
     *                        예외를 삼키면 "삭제 되돌리기가 예외를 삼켜 사고를 낸 이력" 과 같은
     *                        패턴을 검증 코드 자신이 반복하게 된다.
     */
    public static void race(Runnable... actions) {
        if (actions.length < 2) {
            throw new IllegalArgumentException("동시 실행에는 최소 2개의 동작이 필요하다");
        }

        int count = actions.length;
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(count);

        try {
            for (Runnable action : actions) {
                executor.submit(() -> runGated(ready, start, action, failures));
            }
            ready.await();
            start.countDown();
            executor.shutdown();
            boolean terminated = executor.awaitTermination(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!terminated) {
                throw new IllegalStateException(
                        "동시 실행 하네스가 " + AWAIT_TIMEOUT_SECONDS + "초 안에 끝나지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시 실행 대기 중 인터럽트", e);
        } finally {
            executor.shutdownNow();
        }

        if (!failures.isEmpty()) {
            throw new AssertionError("동시 실행 중 " + failures.size() + "건 예외 발생: "
                    + Collections.singletonList(failures.get(0)), failures.get(0));
        }
    }

    private static void runGated(
            CountDownLatch ready, CountDownLatch start, Runnable action, List<Throwable> failures) {
        ready.countDown();
        try {
            start.await();
            action.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | AssertionError e) {
            failures.add(e);
        }
    }
}
