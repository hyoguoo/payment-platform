package com.hyoguoo.paymentplatform.product.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyoguoo.paymentplatform.product.domain.Stock;
import com.hyoguoo.paymentplatform.product.mock.FakeEventDedupeStore;
import com.hyoguoo.paymentplatform.product.mock.FakePaymentStockCachePort;
import com.hyoguoo.paymentplatform.product.mock.FakeStockRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * StockCommitUseCase 단위 테스트.
 * <p>
 * FakeStockRepository + FakeEventDedupeStore + FakePaymentStockCachePort 사용.
 * Kafka / DB / Redis 미연결 순수 usecase 로직만 검증.
 */
class StockCommitUseCaseTest {

    private FakeStockRepository fakeStockRepository;
    private FakeEventDedupeStore fakeEventDedupeStore;
    private FakePaymentStockCachePort fakePaymentStockCachePort;
    private StockCommitUseCase stockCommitUseCase;

    @BeforeEach
    void setUp() {
        fakeStockRepository = new FakeStockRepository();
        fakeEventDedupeStore = new FakeEventDedupeStore();
        fakePaymentStockCachePort = new FakePaymentStockCachePort();
        stockCommitUseCase = new StockCommitUseCase(
                fakeStockRepository,
                fakeEventDedupeStore,
                fakePaymentStockCachePort
        );
    }

    @Test
    @DisplayName("TC1: RDB UPDATE 후 Redis SET 순서대로 호출된다")
    void commit_ShouldUpdateRdbAndSetPaymentRedis() {
        // given
        long productId = 1L;
        String orderId = "order-100";  // K3: String 통일
        String eventUUID = "event-uuid-001";
        int qty = 5;
        int initialStock = 20;

        fakeStockRepository.save(Stock.allArgsBuilder()
                .productId(productId)
                .quantity(initialStock)
                .allArgsBuild());

        Instant expiresAt = Instant.now().plusSeconds(3600);

        // when
        stockCommitUseCase.commit(eventUUID, orderId, productId, qty, expiresAt);

        // then: RDB 업데이트 확인 (재고가 변경됨)
        Stock updated = fakeStockRepository.findByProductId(productId).orElseThrow();
        assertThat(updated.getQuantity()).isEqualTo(initialStock - qty);

        // then: Redis SET 1회 호출
        assertThat(fakePaymentStockCachePort.getSetCallCount()).isEqualTo(1);
        assertThat(fakePaymentStockCachePort.getLatestStock(productId)).isEqualTo(initialStock - qty);
    }

    @Test
    @DisplayName("TC2: 동일 eventUUID 2회 호출 → UPDATE/SET 각 0회 (dedupe)")
    void commit_DuplicateEventUuid_ShouldNoOp() {
        // given
        long productId = 2L;
        String orderId = "order-200";  // K3: String 통일
        String eventUUID = "event-uuid-dup";
        int qty = 3;
        int initialStock = 10;

        fakeStockRepository.save(Stock.allArgsBuilder()
                .productId(productId)
                .quantity(initialStock)
                .allArgsBuild());

        Instant expiresAt = Instant.now().plusSeconds(3600);

        // when: 첫 번째 호출
        stockCommitUseCase.commit(eventUUID, orderId, productId, qty, expiresAt);

        // 첫 번째 호출 후 상태 초기화 (2번째 호출의 영향만 측정)
        fakePaymentStockCachePort.reset();
        // NOTE: FakeStockRepository는 리셋하지 않고 재고 상태 유지

        // when: 두 번째 중복 호출
        stockCommitUseCase.commit(eventUUID, orderId, productId, qty, expiresAt);

        // then: 두 번째 호출은 no-op — Redis SET 0회
        assertThat(fakePaymentStockCachePort.getSetCallCount()).isEqualTo(0);

        // then: RDB 재고는 첫 번째 호출 기준 — 두 번째 호출로 추가 변경 없음
        Stock current = fakeStockRepository.findByProductId(productId).orElseThrow();
        assertThat(current.getQuantity()).isEqualTo(initialStock - qty);
    }

    @Test
    @DisplayName("TC3: RDB UPDATE 실패 시 Redis SET 호출 0회")
    void commit_WhenRdbUpdateFails_ShouldNotSetRedis() {
        // given
        long productId = 3L;
        String orderId = "order-300";  // K3: String 통일
        String eventUUID = "event-uuid-fail";
        int qty = 5;
        // 재고가 없는 상품 → findByProductId → Optional.empty() → IllegalStateException

        Instant expiresAt = Instant.now().plusSeconds(3600);

        // when / then: RDB UPDATE 실패 시 예외 전파
        assertThatThrownBy(() ->
                stockCommitUseCase.commit(eventUUID, orderId, productId, qty, expiresAt))
                .isInstanceOf(IllegalStateException.class);

        // then: Redis SET 호출 0회 (RDB 실패 전에 Set 호출 없음)
        assertThat(fakePaymentStockCachePort.getSetCallCount()).isEqualTo(0);
    }
}
