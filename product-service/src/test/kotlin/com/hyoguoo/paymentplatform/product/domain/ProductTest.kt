package com.hyoguoo.paymentplatform.product.domain

import com.hyoguoo.paymentplatform.product.exception.ProductStockException
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal

@DisplayName("Product 도메인 테스트")
class ProductTest {
    private lateinit var product: Product

    @BeforeEach
    fun setUp() {
        product = Product(1L, "테스트 상품", BigDecimal.valueOf(10000), "설명", 100, 1L)
    }

    // ── decrementStock ────────────────────────────────────────────────────────

    @Test
    @DisplayName("decrementStock: 정상 차감 시 재고가 amount만큼 줄어든다")
    fun decrementStock_whenValidAmount_reducesStock() {
        // given
        val initialStock = product.stock
        val amount = 30

        // when
        product.decrementStock(amount)

        // then
        assertThat(product.stock).isEqualTo(initialStock - amount)
    }

    @Test
    @DisplayName("decrementStock: 재고 전부 차감 시 0이 된다")
    fun decrementStock_whenAmountEqualsStock_stockBecomesZero() {
        // given
        val amount = product.stock

        // when
        product.decrementStock(amount)

        // then
        assertThat(product.stock).isZero
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, -10, Int.MIN_VALUE])
    @DisplayName("decrementStock: 음수 amount 입력 시 NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK 예외")
    fun decrementStock_whenNegativeAmount_throwsProductStockException(negativeAmount: Int) {
        // when & then
        assertThatThrownBy { product.decrementStock(negativeAmount) }
            .isInstanceOfSatisfying(ProductStockException::class.java) { ex ->
                assertThat(ex.errorCode).isEqualTo(ProductErrorCode.NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK)
            }
    }

    @Test
    @DisplayName("decrementStock: amount가 현재 재고보다 크면 NOT_ENOUGH_STOCK 예외")
    fun decrementStock_whenAmountExceedsStock_throwsNotEnoughStock() {
        // given
        val excessAmount = product.stock + 1

        // when & then
        assertThatThrownBy { product.decrementStock(excessAmount) }
            .isInstanceOfSatisfying(ProductStockException::class.java) { ex ->
                assertThat(ex.errorCode).isEqualTo(ProductErrorCode.NOT_ENOUGH_STOCK)
            }
    }

    // ── incrementStock ────────────────────────────────────────────────────────

    @Test
    @DisplayName("incrementStock: 정상 증가 시 재고가 amount만큼 늘어난다")
    fun incrementStock_whenValidAmount_increasesStock() {
        // given
        val initialStock = product.stock
        val amount = 50

        // when
        product.incrementStock(amount)

        // then
        assertThat(product.stock).isEqualTo(initialStock + amount)
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, -5, Int.MIN_VALUE])
    @DisplayName("incrementStock: 음수 amount 입력 시 NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK 예외")
    fun incrementStock_whenNegativeAmount_throwsProductStockException(negativeAmount: Int) {
        // when & then
        assertThatThrownBy { product.incrementStock(negativeAmount) }
            .isInstanceOfSatisfying(ProductStockException::class.java) { ex ->
                assertThat(ex.errorCode).isEqualTo(ProductErrorCode.NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK)
            }
    }
}
