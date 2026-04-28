package com.hyoguoo.paymentplatform.product.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyoguoo.paymentplatform.product.domain.Stock;
import com.hyoguoo.paymentplatform.product.mock.FakeEventDedupeStore;
import com.hyoguoo.paymentplatform.product.mock.FakeStockRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * StockCommitUseCase 단위 테스트.
 * <p>
 * FakeStockRepository + FakeEventDedupeStore 사용. RDB 차감만 검증한다.
 * 새 모델: Redis 동기화 책임은 product-service 가 가지지 않음 — payment-service 가 자기 책임.
 */
class StockCommitUseCaseTest {

    private FakeStockRepository fakeStockRepository;
    private FakeEventDedupeStore fakeEventDedupeStore;
    private StockCommitUseCase stockCommitUseCase;

    @BeforeEach
    void setUp() {
        fakeStockRepository = new FakeStockRepository();
        fakeEventDedupeStore = new FakeEventDedupeStore();
        stockCommitUseCase = new StockCommitUseCase(
                fakeStockRepository,
                fakeEventDedupeStore
        );
    }

    @Test
    @DisplayName("TC1: 첫 호출 시 RDB 재고가 qty 만큼 차감된다")
    void commit_ShouldDecreaseRdbStock() {
        long productId = 1L;
        String orderId = "order-100";
        String eventUUID = "event-uuid-001";
        int qty = 5;
        int initialStock = 20;

        fakeStockRepository.save(Stock.allArgsBuilder()
                .productId(productId)
                .quantity(initialStock)
                .allArgsBuild());

        Instant expiresAt = Instant.now().plusSeconds(3600);

        stockCommitUseCase.commit(eventUUID, orderId, productId, qty, expiresAt);

        Stock updated = fakeStockRepository.findByProductId(productId).orElseThrow();
        assertThat(updated.getQuantity()).isEqualTo(initialStock - qty);
    }

    @Test
    @DisplayName("TC2: 동일 eventUUID 2회 호출 → 두 번째는 no-op (dedupe)")
    void commit_DuplicateEventUuid_ShouldNoOp() {
        long productId = 2L;
        String orderId = "order-200";
        String eventUUID = "event-uuid-dup";
        int qty = 3;
        int initialStock = 10;

        fakeStockRepository.save(Stock.allArgsBuilder()
                .productId(productId)
                .quantity(initialStock)
                .allArgsBuild());

        Instant expiresAt = Instant.now().plusSeconds(3600);

        stockCommitUseCase.commit(eventUUID, orderId, productId, qty, expiresAt);
        stockCommitUseCase.commit(eventUUID, orderId, productId, qty, expiresAt);

        // 두 번째 호출은 dedupe 로 무시 → 재고는 첫 번째 호출 기준
        Stock current = fakeStockRepository.findByProductId(productId).orElseThrow();
        assertThat(current.getQuantity()).isEqualTo(initialStock - qty);
    }

    @Test
    @DisplayName("TC3: 재고 row 미존재 시 IllegalStateException 전파")
    void commit_WhenStockNotFound_ShouldThrow() {
        long productId = 3L;
        String orderId = "order-300";
        String eventUUID = "event-uuid-fail";
        int qty = 5;

        Instant expiresAt = Instant.now().plusSeconds(3600);

        assertThatThrownBy(() ->
                stockCommitUseCase.commit(eventUUID, orderId, productId, qty, expiresAt))
                .isInstanceOf(IllegalStateException.class);
    }
}
