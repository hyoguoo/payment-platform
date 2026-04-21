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
 * StockRestoreUseCase 단위 테스트.
 * <p>
 * FakeStockRepository + FakeEventDedupeStore 사용.
 * Kafka / DB 미연결 순수 usecase 로직만 검증.
 * <p>
 * 불변식 14: 동일 eventUuid 재처리 금지 (TTL 미만료 시).
 * 처리 순서: dedupe check → 재고 조회·증가·save → dedupe 기록 (재고 성공 후만).
 */
class StockRestoreUseCaseTest {

    private FakeStockRepository fakeStockRepository;
    private FakeEventDedupeStore fakeEventDedupeStore;
    private StockRestoreUseCase stockRestoreUseCase;

    @BeforeEach
    void setUp() {
        fakeStockRepository = new FakeStockRepository();
        fakeEventDedupeStore = new FakeEventDedupeStore();
        stockRestoreUseCase = new StockRestoreUseCase(fakeStockRepository, fakeEventDedupeStore);
    }

    @Test
    @DisplayName("TC-R1: restore 호출 시 재고가 qty만큼 증가한다")
    void restore_ShouldIncreaseStock() {
        // given
        long productId = 1L;
        String orderId = "order-001";
        String eventUuid = "event-uuid-r1";
        int initialStock = 10;
        int restoreQty = 5;

        fakeStockRepository.save(Stock.allArgsBuilder()
                .productId(productId)
                .quantity(initialStock)
                .allArgsBuild());

        // when
        stockRestoreUseCase.restore(orderId, eventUuid, productId, restoreQty);

        // then: 재고가 restoreQty만큼 증가
        Stock updated = fakeStockRepository.findByProductId(productId).orElseThrow();
        assertThat(updated.getQuantity()).isEqualTo(initialStock + restoreQty);
    }

    @Test
    @DisplayName("TC-R2: 동일 eventUuid 2회 호출 → 두 번째는 no-op (불변식 14)")
    void restore_DuplicateEventUuid_ShouldNoOp() {
        // given
        long productId = 2L;
        String orderId = "order-002";
        String eventUuid = "event-uuid-dup";
        int initialStock = 10;
        int restoreQty = 3;

        fakeStockRepository.save(Stock.allArgsBuilder()
                .productId(productId)
                .quantity(initialStock)
                .allArgsBuild());

        // when: 첫 번째 호출
        stockRestoreUseCase.restore(orderId, eventUuid, productId, restoreQty);

        // when: 두 번째 중복 호출
        stockRestoreUseCase.restore(orderId, eventUuid, productId, restoreQty);

        // then: 재고는 첫 번째 호출만 반영 — 두 번째는 no-op
        Stock current = fakeStockRepository.findByProductId(productId).orElseThrow();
        assertThat(current.getQuantity()).isEqualTo(initialStock + restoreQty);
    }

    @Test
    @DisplayName("TC-R3: TTL 만료 후 동일 eventUuid 재처리 → 재고 증가 1회")
    void restore_AfterDedupeTtlExpiry_ShouldReprocessOnce() {
        // given
        long productId = 3L;
        String orderId = "order-003";
        String eventUuid = "event-uuid-ttl";
        int initialStock = 10;
        int restoreQty = 2;

        fakeStockRepository.save(Stock.allArgsBuilder()
                .productId(productId)
                .quantity(initialStock)
                .allArgsBuild());

        // 만료된 dedupe 엔트리를 수동으로 심기 (expiresAt = 과거 → 만료)
        // FakeEventDedupeStore.recordIfAbsent 는 existing.isBefore(Instant.now(clock)) 이면 덮어씀
        fakeEventDedupeStore.recordIfAbsent(eventUuid, Instant.now().minusSeconds(10));

        // when: TTL 만료 후 재처리
        stockRestoreUseCase.restore(orderId, eventUuid, productId, restoreQty);

        // then: 재고가 restoreQty만큼 증가 (1회 재처리)
        Stock updated = fakeStockRepository.findByProductId(productId).orElseThrow();
        assertThat(updated.getQuantity()).isEqualTo(initialStock + restoreQty);
    }

    @Test
    @DisplayName("TC-R4: 재고 증가 실패(상품 미존재) 시 dedupe 미기록")
    void restore_WhenStockIncreaseFailsMidway_ShouldNotRecordDedupe() {
        // given: 존재하지 않는 productId → 재고 조회 실패 → IllegalStateException
        long productId = 999L;
        String orderId = "order-999";
        String eventUuid = "event-uuid-fail";
        int restoreQty = 5;

        // when / then: 예외 전파
        assertThatThrownBy(() ->
                stockRestoreUseCase.restore(orderId, eventUuid, productId, restoreQty))
                .isInstanceOf(IllegalStateException.class);

        // then: dedupe 미기록 (재고 증가 실패 → dedupe store에 기록하지 않음)
        assertThat(fakeEventDedupeStore.contains(eventUuid)).isFalse();
    }
}
