package com.hyoguoo.paymentplatform.product.mock

import com.hyoguoo.paymentplatform.product.domain.Stock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Optional

class FakeStockRepositoryTest {
    private lateinit var repository: FakeStockRepository

    @BeforeEach
    fun setUp() {
        repository = FakeStockRepository()
    }

    @Test
    @DisplayName("save 후 findByProductId — 왕복 검증")
    fun saveAndFindByProductId_Roundtrip() {
        val stock = Stock(1L, 100)

        repository.save(stock)
        val found: Optional<Stock> = repository.findByProductId(1L)

        assertThat(found).isPresent
        assertThat(found.get().productId).isEqualTo(1L)
        assertThat(found.get().quantity).isEqualTo(100)
    }

    @Test
    @DisplayName("findByProductId — 존재하지 않는 상품은 empty")
    fun findByProductId_NotFound_ReturnsEmpty() {
        val found: Optional<Stock> = repository.findByProductId(999L)

        assertThat(found).isEmpty
    }

    @Test
    @DisplayName("increment — 원자적 재고 증가")
    fun increment_IncreasesStock() {
        repository.save(Stock(1L, 50))

        val after = repository.increment(1L, 10)

        assertThat(after).isEqualTo(60)
        assertThat(repository.findByProductId(1L).get().quantity).isEqualTo(60)
    }

    @Test
    @DisplayName("decrement — 재고 충분 시 감소 성공")
    fun decrement_SufficientStock_ReturnsTrue() {
        repository.save(Stock(1L, 50))

        val success = repository.decrement(1L, 30)

        assertThat(success).isTrue
        assertThat(repository.findByProductId(1L).get().quantity).isEqualTo(20)
    }

    @Test
    @DisplayName("decrement — 재고 부족 시 감소 실패 (음수 방어)")
    fun decrement_InsufficientStock_ReturnsFalse() {
        repository.save(Stock(1L, 5))

        val success = repository.decrement(1L, 10)

        assertThat(success).isFalse
        assertThat(repository.findByProductId(1L).get().quantity).isEqualTo(5)
    }
}
