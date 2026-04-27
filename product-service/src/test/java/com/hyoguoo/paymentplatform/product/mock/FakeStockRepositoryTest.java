package com.hyoguoo.paymentplatform.product.mock;

import com.hyoguoo.paymentplatform.product.domain.Stock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FakeStockRepositoryTest {

    private FakeStockRepository repository;

    @BeforeEach
    void setUp() {
        repository = new FakeStockRepository();
    }

    @Test
    @DisplayName("save 후 findByProductId — 왕복 검증")
    void saveAndFindByProductId_Roundtrip() {
        Stock stock = Stock.allArgsBuilder()
                .productId(1L)
                .quantity(100)
                .allArgsBuild();

        repository.save(stock);
        Optional<Stock> found = repository.findByProductId(1L);

        assertThat(found).isPresent();
        assertThat(found.get().getProductId()).isEqualTo(1L);
        assertThat(found.get().getQuantity()).isEqualTo(100);
    }

    @Test
    @DisplayName("findByProductId — 존재하지 않는 상품은 empty")
    void findByProductId_NotFound_ReturnsEmpty() {
        Optional<Stock> found = repository.findByProductId(999L);

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("increment — 원자적 재고 증가")
    void increment_IncreasesStock() {
        repository.save(Stock.allArgsBuilder().productId(1L).quantity(50).allArgsBuild());

        int after = repository.increment(1L, 10);

        assertThat(after).isEqualTo(60);
        assertThat(repository.findByProductId(1L).get().getQuantity()).isEqualTo(60);
    }

    @Test
    @DisplayName("decrement — 재고 충분 시 감소 성공")
    void decrement_SufficientStock_ReturnsTrue() {
        repository.save(Stock.allArgsBuilder().productId(1L).quantity(50).allArgsBuild());

        boolean success = repository.decrement(1L, 30);

        assertThat(success).isTrue();
        assertThat(repository.findByProductId(1L).get().getQuantity()).isEqualTo(20);
    }

    @Test
    @DisplayName("decrement — 재고 부족 시 감소 실패 (음수 방어)")
    void decrement_InsufficientStock_ReturnsFalse() {
        repository.save(Stock.allArgsBuilder().productId(1L).quantity(5).allArgsBuild());

        boolean success = repository.decrement(1L, 10);

        assertThat(success).isFalse();
        assertThat(repository.findByProductId(1L).get().getQuantity()).isEqualTo(5);
    }
}
