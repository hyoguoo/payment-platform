package com.hyoguoo.paymentplatform.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockDecrementAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordSnapshot;
import com.hyoguoo.paymentplatform.payment.application.util.StockHoldReverter;
import com.hyoguoo.paymentplatform.payment.core.test.BaseIntegrationTest;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.StockHoldRecordStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 거절 후 재시도가 실제로 재고까지 되돌아오는지, 그리고 되돌리기와 기록 닫기 사이의 창에 새
 * 차감이 끼어들어도 뒤늦은 닫기가 그 차감을 덮지 않는지 검증한다.
 *
 * <p>동시 중복 확정 차단은 {@code PaymentDuplicateConfirmConcurrencyIntegrationTest}가, 완료된
 * 결제 재확정 시 캐시 호출이 없는 것은 {@code PaymentTransactionCoordinatorTest}가 이미 값
 * 단정까지 포함해 검증한다 — 이 클래스는 그 두 시나리오와 겹치지 않는 나머지 둘만 다룬다.
 */
@DisplayName("재고 게이트 거절-재시도 재고 정합 및 닫기 경합 통합 테스트")
class StockGateConcurrentRetryIntegrationTest extends BaseIntegrationTest {

    private static final int INITIAL_STOCK = 1_000;
    private static final int QUANTITY = 5;

    private static final AtomicLong PRODUCT_ID_SEQUENCE = new AtomicLong(System.nanoTime());

    @Autowired
    private StockCachePort stockCachePort;

    @Autowired
    private StockHoldRecordRepository stockHoldRecordRepository;

    @Autowired
    @Qualifier("stockCacheRedisTemplate")
    private StringRedisTemplate stockRedisTemplate;

    @Test
    @DisplayName("거절 후 재시도가 그 상품을 다시 차감하고, 그 차감이 되돌아가면 재고가 실제로 원래 값으로 복원된다")
    void 거절_후_재시도_차감이_실제로_재고까지_되돌아온다() {
        // given — 1주기: 직접 차감 후 거절 전용 되돌리기로 두 표시를 함께 지운다
        long productId = PRODUCT_ID_SEQUENCE.incrementAndGet();
        String orderId = "order-reject-retry-" + UUID.randomUUID();
        PaymentOrder order = buildOrder(orderId, productId, QUANTITY);
        stockRedisTemplate.opsForValue().set(stockKey(productId), String.valueOf(INITIAL_STOCK));

        stockHoldRecordRepository.openHold(orderId, order);
        StockDecrementAtomicResult firstDecrement = stockCachePort.decrementAtomic(orderId, order);
        assertThat(firstDecrement).isEqualTo(StockDecrementAtomicResult.OK);

        stockCachePort.rejectCompensate(orderId, order);
        assertThat(currentStock(productId))
                .as("사전조건 — 거절 전용 되돌리기 직후 재고가 원래 값으로 복원돼야 한다")
                .isEqualTo(INITIAL_STOCK);

        // when — 2주기: 두 표시가 지워졌으므로 같은 조합이 다시 직접 차감된다(이미 처리됨이 아니다)
        String secondCycleToken = stockHoldRecordRepository.openHold(orderId, order);
        StockDecrementAtomicResult secondDecrement = stockCachePort.decrementAtomic(orderId, order);
        assertThat(secondDecrement)
                .as("거절이 두 표시를 함께 지웠으므로 재시도는 이미 처리됨이 아니라 실제로 다시 차감돼야 한다")
                .isEqualTo(StockDecrementAtomicResult.OK);
        assertThat(currentStock(productId)).isEqualTo(INITIAL_STOCK - QUANTITY);

        // 2주기가 벤더 실패로 종결돼 조건부 되돌리기를 탄다
        stockCachePort.compensateIfDecremented(orderId, order);
        stockHoldRecordRepository.closeAsReverted(orderId, order, secondCycleToken);

        // then — 기록만 REVERTED 로 닫혀서는 부족하다. 되돌리기 표시가 앞 주기에서 남아 있었다면
        // 여기서 ALREADY_DONE 으로 흡수돼 재고가 차감된 채(990) 봉인됐을 것이다
        assertThat(currentStock(productId))
                .as("두 번째 차감도 실제로 재고까지 원래 값으로 복원돼야 한다")
                .isEqualTo(INITIAL_STOCK);
        StockHoldRecordSnapshot snapshot = stockHoldRecordRepository.findSnapshot(orderId, order).orElseThrow();
        assertThat(snapshot.status()).isEqualTo(StockHoldRecordStatus.REVERTED);
    }

    @Test
    @DisplayName("닫기 경합 — 캐시 되돌리기 후 기록 닫기 전에 새 차감이 들어오면 뒤늦은 닫기가 그 차감을 덮지 않는다")
    void 닫기_경합_뒤늦은_닫기가_새_차감을_덮지_않는다() throws Exception {
        // given — 거절로 종결된 1주기의 기록은 닫히지 않은 채 남는다(거절 전용은 캐시만 되돌리고
        // 기록은 건드리지 않는다) — 이 잔여 기록을 뒤늦게 되돌리려는 주체가 있다고 가정한다
        long productId = PRODUCT_ID_SEQUENCE.incrementAndGet();
        String orderId = "order-close-race-" + UUID.randomUUID();
        PaymentOrder order = buildOrder(orderId, productId, QUANTITY);
        stockRedisTemplate.opsForValue().set(stockKey(productId), String.valueOf(INITIAL_STOCK));

        String staleCycleToken = stockHoldRecordRepository.openHold(orderId, order);
        StockDecrementAtomicResult firstDecrement = stockCachePort.decrementAtomic(orderId, order);
        assertThat(firstDecrement).isEqualTo(StockDecrementAtomicResult.OK);
        stockCachePort.rejectCompensate(orderId, order);

        CountDownLatch pauseReachedBeforeClose = new CountDownLatch(1);
        CountDownLatch resumeCloseSignal = new CountDownLatch(1);
        StockHoldReverter delayedReverter = new StockHoldReverter(new SimpleMeterRegistry()) {
            @Override
            protected void beforeClose(String hookOrderId, PaymentOrder hookOrder) {
                pauseReachedBeforeClose.countDown();
                awaitQuietly(resumeCloseSignal);
            }
        };

        ExecutorService executor = Executors.newSingleThreadExecutor();
        String newCycleToken;
        try {
            Future<?> lateRevertFuture = executor.submit(() -> delayedReverter.revertProductHold(
                    orderId, order, staleCycleToken, stockCachePort, stockHoldRecordRepository));

            awaitOrFail(pauseReachedBeforeClose);

            // when — 되돌리기와 닫기 사이의 창에 같은 조합의 새 차감이 들어온다(재시도)
            newCycleToken = stockHoldRecordRepository.openHold(orderId, order);
            StockDecrementAtomicResult newDecrement = stockCachePort.decrementAtomic(orderId, order);
            assertThat(newDecrement)
                    .as("거절이 두 표시를 지웠으므로 새 차감도 실제로 성공해야 한다")
                    .isEqualTo(StockDecrementAtomicResult.OK);
            assertThat(newCycleToken).isNotEqualTo(staleCycleToken);

            resumeCloseSignal.countDown();
            lateRevertFuture.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        // then — 뒤늦은 닫기는 옛 사이클 식별 값을 들고 있어 반영되지 않는다. 반영됐다면 새 차감이
        // 진행 중인데도 기록이 REVERTED 로 닫혀 회수 대상에서 영영 빠지는 유령 누수가 됐을 것이다
        StockHoldRecordSnapshot snapshot = stockHoldRecordRepository.findSnapshot(orderId, order).orElseThrow();
        assertThat(snapshot.status())
                .as("새 차감이 연 사이클은 뒤늦은 닫기에 덮이지 않고 잡음 상태 그대로 남아야 한다")
                .isEqualTo(StockHoldRecordStatus.NOISE);
        assertThat(snapshot.cycleToken()).isEqualTo(newCycleToken);
        assertThat(currentStock(productId))
                .as("새 차감은 뒤늦은 닫기와 무관하게 그대로 유지돼야 한다")
                .isEqualTo(INITIAL_STOCK - QUANTITY);
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    private int currentStock(long productId) {
        String value = stockRedisTemplate.opsForValue().get(stockKey(productId));
        return Integer.parseInt(value);
    }

    private String stockKey(long productId) {
        return "stock:{" + productId + "}";
    }

    private PaymentOrder buildOrder(String orderId, long productId, int quantity) {
        return PaymentOrder.allArgsBuilder()
                .orderId(orderId)
                .productId(productId)
                .quantity(quantity)
                .totalAmount(BigDecimal.valueOf(1_000L * quantity))
                .status(PaymentOrderStatus.EXECUTING)
                .allArgsBuild();
    }

    private void awaitOrFail(CountDownLatch latch) throws InterruptedException {
        boolean reached = latch.await(10, TimeUnit.SECONDS);
        if (!reached) {
            throw new IllegalStateException("지연 주입 지점에 10초 안에 도달하지 못했다");
        }
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기 중 인터럽트", e);
        }
    }
}
