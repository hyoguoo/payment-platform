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
    @DisplayName("첫 호출 시 RDB 재고가 qty 만큼 차감된다")
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

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(3600);

        stockCommitUseCase.commit(eventUUID, orderId, productId, qty, now, expiresAt);

        Stock updated = fakeStockRepository.findByProductId(productId).orElseThrow();
        assertThat(updated.getQuantity()).isEqualTo(initialStock - qty);
    }

    @Test
    @DisplayName("동일 eventUUID 2회 호출 → 두 번째는 no-op (dedupe)")
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

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(3600);

        stockCommitUseCase.commit(eventUUID, orderId, productId, qty, now, expiresAt);
        stockCommitUseCase.commit(eventUUID, orderId, productId, qty, now, expiresAt);

        // 두 번째 호출은 dedupe 로 무시 → 재고는 첫 번째 호출 기준
        Stock current = fakeStockRepository.findByProductId(productId).orElseThrow();
        assertThat(current.getQuantity()).isEqualTo(initialStock - qty);
    }

    @Test
    @DisplayName("재고 row 미존재 시 IllegalStateException 전파")
    void commit_WhenStockNotFound_ShouldThrow() {
        long productId = 3L;
        String orderId = "order-300";
        String eventUUID = "event-uuid-fail";
        int qty = 5;

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(3600);

        assertThatThrownBy(() ->
                stockCommitUseCase.commit(eventUUID, orderId, productId, qty, now, expiresAt))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("중복 이벤트: recordIfAbsent에 now가 전달되고 false 반환 시 commitToRdb 미호출")
    void commit_중복이벤트_recordIfAbsent에now전달_false반환시스킵() {
        long productId = 4L;
        String orderId = "order-400";
        String eventUUID = "event-uuid-dup-now";
        int qty = 2;
        int initialStock = 15;

        fakeStockRepository.save(Stock.allArgsBuilder()
                .productId(productId)
                .quantity(initialStock)
                .allArgsBuild());

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(3600);

        // 첫 번째 호출: true 반환 → 재고 차감
        stockCommitUseCase.commit(eventUUID, orderId, productId, qty, now, expiresAt);

        // 두 번째 호출: false 반환 → commitToRdb 미호출
        stockCommitUseCase.commit(eventUUID, orderId, productId, qty, now, expiresAt);

        // Fake 에 now 가 전달됐는지는 FakeEventDedupeStore.contains 로 확인
        assertThat(fakeEventDedupeStore.contains(eventUUID)).isTrue();
        // 재고는 첫 번째 호출분만 차감
        Stock current = fakeStockRepository.findByProductId(productId).orElseThrow();
        assertThat(current.getQuantity()).isEqualTo(initialStock - qty);
    }

    @Test
    @DisplayName("최초 이벤트: recordIfAbsent true 반환 시 재고 차감 성공")
    void commit_최초이벤트_재고차감성공() {
        long productId = 5L;
        String orderId = "order-500";
        String eventUUID = "event-uuid-first";
        int qty = 7;
        int initialStock = 30;

        fakeStockRepository.save(Stock.allArgsBuilder()
                .productId(productId)
                .quantity(initialStock)
                .allArgsBuild());

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(3600);

        stockCommitUseCase.commit(eventUUID, orderId, productId, qty, now, expiresAt);

        // FakeEventDedupeStore 에 UUID 가 기록됐고 재고 차감 완료
        assertThat(fakeEventDedupeStore.contains(eventUUID)).isTrue();
        Stock current = fakeStockRepository.findByProductId(productId).orElseThrow();
        assertThat(current.getQuantity()).isEqualTo(initialStock - qty);
    }
}
