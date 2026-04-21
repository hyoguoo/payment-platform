package com.hyoguoo.paymentplatform.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.mock.FakePaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderedProductStockCommand;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.infrastructure.metrics.StockCacheDivergenceMetrics;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockCachePort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PaymentReconciler 테스트")
class PaymentReconcilerTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 4, 21, 12, 0, 0);
    private static final long IN_FLIGHT_TIMEOUT_SECONDS = 300L; // 5분

    private FakePaymentEventRepository fakePaymentEventRepository;
    private FakeStockCachePort fakeStockCachePort;
    private FakeProductPort fakeProductPort;
    private SimpleMeterRegistry meterRegistry;
    private StockCacheDivergenceMetrics divergenceMetrics;
    private PaymentReconciler reconciler;

    @BeforeEach
    void setUp() {
        fakePaymentEventRepository = new FakePaymentEventRepository();
        fakeStockCachePort = new FakeStockCachePort();
        fakeProductPort = new FakeProductPort();
        meterRegistry = new SimpleMeterRegistry();
        divergenceMetrics = new StockCacheDivergenceMetrics(meterRegistry);
        reconciler = new PaymentReconciler(
                fakePaymentEventRepository,
                fakeStockCachePort,
                fakeProductPort,
                () -> FIXED_NOW,
                divergenceMetrics,
                IN_FLIGHT_TIMEOUT_SECONDS
        );
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 1. IN_FLIGHT + timeout 초과 → PENDING(READY) 복원
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("scan - IN_FLIGHT + timeout 초과 레코드를 발견하면 PENDING(READY) 상태로 복원한다")
    void scan_FindsStaleInFlightRecords_AndResetsToRetry() {
        // given: IN_PROGRESS 상태인데 executedAt이 timeout 전인 레코드
        LocalDateTime staleTime = FIXED_NOW.minusSeconds(IN_FLIGHT_TIMEOUT_SECONDS + 60);
        PaymentEvent staleEvent = buildInProgressEvent("order-stale-001", staleTime);
        fakePaymentEventRepository.saveOrUpdate(staleEvent);

        // when
        reconciler.scan();

        // then: READY 상태로 복원됨
        PaymentEvent result = fakePaymentEventRepository.findByOrderId("order-stale-001").orElseThrow();
        assertThat(result.getStatus()).isEqualTo(PaymentEventStatus.READY);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. DONE/FAILED/QUARANTINED 터미널 상태 — 불간섭
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("scan - DONE/FAILED/QUARANTINED 상태 레코드는 건드리지 않는다")
    void scan_DoesNotTouchTerminalRecords() {
        // given: 각각 DONE, FAILED, QUARANTINED 상태의 이벤트 (product 1의 재고 충분히 준비)
        Long productId = 100L;
        fakeProductPort.register(productId, 1000);
        fakeStockCachePort.set(productId, 1000);

        PaymentEvent doneEvent = buildTerminalEvent("order-done-001", PaymentEventStatus.DONE, productId, 1);
        PaymentEvent failedEvent = buildTerminalEvent("order-failed-001", PaymentEventStatus.FAILED, productId, 1);
        PaymentEvent quarantinedEvent = buildQuarantinedEvent("order-quarantine-001", productId, 1, false);

        fakePaymentEventRepository.saveOrUpdate(doneEvent);
        fakePaymentEventRepository.saveOrUpdate(failedEvent);
        fakePaymentEventRepository.saveOrUpdate(quarantinedEvent);

        // when
        reconciler.scan();

        // then: 각 상태 그대로 유지
        assertThat(fakePaymentEventRepository.findByOrderId("order-done-001").orElseThrow().getStatus())
                .isEqualTo(PaymentEventStatus.DONE);
        assertThat(fakePaymentEventRepository.findByOrderId("order-failed-001").orElseThrow().getStatus())
                .isEqualTo(PaymentEventStatus.FAILED);
        assertThat(fakePaymentEventRepository.findByOrderId("order-quarantine-001").orElseThrow().getStatus())
                .isEqualTo(PaymentEventStatus.QUARANTINED);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. 재고 발산 감지 → Redis 재설정 + divergence_count +1
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("scan - Redis 재고가 RDB 기준값과 다를 때 Redis를 RDB값으로 재설정하고 divergence_count를 올린다")
    void scan_WhenStockCacheDivergesFromRdb_ShouldResetCacheToRdbValue() {
        // given: product 100의 RDB 재고=50, READY 결제 1건(수량 5) → 예상 Redis값 = 50-5 = 45
        //        현재 Redis값 = 30 → 발산
        Long productId = 100L;
        int rdbStock = 50;
        int lockedQuantity = 5;
        int divergedCacheValue = 30;

        fakeProductPort.register(productId, rdbStock);
        fakeStockCachePort.set(productId, divergedCacheValue);

        PaymentEvent readyEvent = buildReadyEvent("order-ready-001", productId, lockedQuantity);
        fakePaymentEventRepository.saveOrUpdate(readyEvent);

        Counter counterBefore = meterRegistry.find(StockCacheDivergenceMetrics.METRIC_NAME).counter();
        double divergenceBefore = counterBefore != null ? counterBefore.count() : 0.0;

        // when
        reconciler.scan();

        // then: Redis가 RDB 기준값으로 재설정됨
        int expectedCacheValue = rdbStock - lockedQuantity;
        assertThat(fakeStockCachePort.current(productId)).isEqualTo(expectedCacheValue);
        // divergence_count +1
        Counter counter = meterRegistry.find(StockCacheDivergenceMetrics.METRIC_NAME).counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(divergenceBefore + 1.0);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 4. QUARANTINED 결제 → StockCachePort.rollback() 호출
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("scan - QUARANTINED 결제가 있으면 각 주문의 DECR 수량을 rollback() 호출로 복원한다")
    void scan_WhenQuarantinedPaymentExists_ShouldRollbackDecrForEach() {
        // given: QUARANTINED 이벤트, 주문 수량 5
        Long productId = 200L;
        int quantity = 5;
        fakeProductPort.register(productId, 100);
        fakeStockCachePort.set(productId, 90); // 10 차감된 상태 (실제로는 5만 차감됐어야 하는데 여기선 단순 테스트)

        PaymentEvent quarantinedEvent = buildQuarantinedEvent("order-q-001", productId, quantity, false);
        fakePaymentEventRepository.saveOrUpdate(quarantinedEvent);

        int cacheBefore = fakeStockCachePort.current(productId);

        // when
        reconciler.scan();

        // then: rollback() 호출로 재고 복원 (+5)
        assertThat(fakeStockCachePort.current(productId)).isEqualTo(cacheBefore + quantity);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 5. stock cache key miss → RDB 기준 SET
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("scan - Redis stock key가 없으면(TTL 만료) RDB 기준으로 캐시를 재설정한다")
    void scan_WhenStockCacheKeyMissing_ShouldRestoreFromRdb() {
        // given: product 300의 RDB 재고=80, READY 결제 1건(수량 3) → 예상값 = 77
        //        Redis key 없음 (TTL 만료)
        Long productId = 300L;
        int rdbStock = 80;
        int lockedQuantity = 3;

        fakeProductPort.register(productId, rdbStock);
        // fakeStockCachePort에 key 설정 안 함 → miss

        PaymentEvent readyEvent = buildReadyEvent("order-ready-300", productId, lockedQuantity);
        fakePaymentEventRepository.saveOrUpdate(readyEvent);

        // when
        reconciler.scan();

        // then: RDB 기준값으로 Redis SET
        int expectedCacheValue = rdbStock - lockedQuantity;
        assertThat(fakeStockCachePort.current(productId)).isEqualTo(expectedCacheValue);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // builder helpers
    // ──────────────────────────────────────────────────────────────────────────

    private PaymentEvent buildInProgressEvent(String orderId, LocalDateTime executedAt) {
        return PaymentEvent.allArgsBuilder()
                .id(null)
                .buyerId(1L)
                .sellerId(2L)
                .orderName("상품 외 1건")
                .orderId(orderId)
                .paymentKey("pk-" + orderId)
                .gatewayType(PaymentGatewayType.TOSS)
                .status(PaymentEventStatus.IN_PROGRESS)
                .executedAt(executedAt)
                .retryCount(0)
                .paymentOrderList(new ArrayList<>())
                .quarantineCompensationPending(false)
                .lastStatusChangedAt(executedAt)
                .allArgsBuild();
    }

    private PaymentEvent buildTerminalEvent(String orderId, PaymentEventStatus status, Long productId, int quantity) {
        PaymentOrder order = PaymentOrder.allArgsBuilder()
                .id(null)
                .paymentEventId(null)
                .orderId(orderId)
                .productId(productId)
                .quantity(quantity)
                .totalAmount(BigDecimal.valueOf(1000L * quantity))
                .allArgsBuild();
        return PaymentEvent.allArgsBuilder()
                .id(null)
                .buyerId(1L)
                .sellerId(2L)
                .orderName("상품 외 1건")
                .orderId(orderId)
                .paymentKey("pk-" + orderId)
                .gatewayType(PaymentGatewayType.TOSS)
                .status(status)
                .retryCount(0)
                .paymentOrderList(new ArrayList<>(List.of(order)))
                .quarantineCompensationPending(false)
                .lastStatusChangedAt(FIXED_NOW)
                .allArgsBuild();
    }

    private PaymentEvent buildQuarantinedEvent(String orderId, Long productId, int quantity, boolean compensationPending) {
        PaymentOrder order = PaymentOrder.allArgsBuilder()
                .id(null)
                .paymentEventId(null)
                .orderId(orderId)
                .productId(productId)
                .quantity(quantity)
                .totalAmount(BigDecimal.valueOf(1000L * quantity))
                .allArgsBuild();
        return PaymentEvent.allArgsBuilder()
                .id(null)
                .buyerId(1L)
                .sellerId(2L)
                .orderName("상품 외 1건")
                .orderId(orderId)
                .paymentKey("pk-" + orderId)
                .gatewayType(PaymentGatewayType.TOSS)
                .status(PaymentEventStatus.QUARANTINED)
                .retryCount(0)
                .paymentOrderList(new ArrayList<>(List.of(order)))
                .quarantineCompensationPending(compensationPending)
                .lastStatusChangedAt(FIXED_NOW)
                .allArgsBuild();
    }

    private PaymentEvent buildReadyEvent(String orderId, Long productId, int quantity) {
        PaymentOrder order = PaymentOrder.allArgsBuilder()
                .id(null)
                .paymentEventId(null)
                .orderId(orderId)
                .productId(productId)
                .quantity(quantity)
                .totalAmount(BigDecimal.valueOf(1000L * quantity))
                .allArgsBuild();
        return PaymentEvent.allArgsBuilder()
                .id(null)
                .buyerId(1L)
                .sellerId(2L)
                .orderName("상품 외 1건")
                .orderId(orderId)
                .paymentKey(null)
                .gatewayType(PaymentGatewayType.TOSS)
                .status(PaymentEventStatus.READY)
                .retryCount(0)
                .paymentOrderList(new ArrayList<>(List.of(order)))
                .quarantineCompensationPending(false)
                .lastStatusChangedAt(FIXED_NOW.minusHours(1))
                .allArgsBuild();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FakeProductPort — inner class
    // ──────────────────────────────────────────────────────────────────────────

    static class FakeProductPort implements ProductPort {

        private final Map<Long, Integer> stockDatabase = new HashMap<>();

        void register(Long productId, int stock) {
            stockDatabase.put(productId, stock);
        }

        @Override
        public ProductInfo getProductInfoById(Long productId) {
            int stock = stockDatabase.getOrDefault(productId, 0);
            return ProductInfo.builder()
                    .id(productId)
                    .name("상품-" + productId)
                    .price(BigDecimal.valueOf(1000))
                    .stock(stock)
                    .sellerId(99L)
                    .build();
        }

        @Override
        public void decreaseStockForOrders(List<OrderedProductStockCommand> orderedProductStockCommandList) {
            // Reconciler 테스트에서는 사용하지 않음
        }

        @Override
        public void increaseStockForOrders(List<OrderedProductStockCommand> orderedProductStockCommandList) {
            // Reconciler 테스트에서는 사용하지 않음
        }
    }
}
