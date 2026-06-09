package com.hyoguoo.paymentplatform.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyoguoo.paymentplatform.product.exception.ProductStockException;
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Product 도메인 테스트")
class ProductTest {

    private Product product;

    @BeforeEach
    void setUp() {
        product = Product.allArgsBuilder()
                .id(1L)
                .name("테스트 상품")
                .price(BigDecimal.valueOf(10000))
                .description("설명")
                .stock(100)
                .sellerId(1L)
                .allArgsBuild();
    }

    // ── decrementStock ────────────────────────────────────────────────────────

    @Test
    @DisplayName("decrementStock: 정상 차감 시 재고가 amount만큼 줄어든다")
    void decrementStock_whenValidAmount_reducesStock() {
        // given
        int initialStock = product.getStock();
        int amount = 30;

        // when
        product.decrementStock(amount);

        // then
        assertThat(product.getStock()).isEqualTo(initialStock - amount);
    }

    @Test
    @DisplayName("decrementStock: 재고 전부 차감 시 0이 된다")
    void decrementStock_whenAmountEqualsStock_stockBecomesZero() {
        // given
        int amount = product.getStock();

        // when
        product.decrementStock(amount);

        // then
        assertThat(product.getStock()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -10, Integer.MIN_VALUE})
    @DisplayName("decrementStock: 음수 amount 입력 시 NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK 예외")
    void decrementStock_whenNegativeAmount_throwsProductStockException(int negativeAmount) {
        // when & then
        assertThatThrownBy(() -> product.decrementStock(negativeAmount))
                .isInstanceOf(ProductStockException.class)
                .satisfies(ex -> assertThat(((ProductStockException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK));
    }

    @Test
    @DisplayName("decrementStock: amount가 현재 재고보다 크면 NOT_ENOUGH_STOCK 예외")
    void decrementStock_whenAmountExceedsStock_throwsNotEnoughStock() {
        // given
        int excessAmount = product.getStock() + 1;

        // when & then
        assertThatThrownBy(() -> product.decrementStock(excessAmount))
                .isInstanceOf(ProductStockException.class)
                .satisfies(ex -> assertThat(((ProductStockException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.NOT_ENOUGH_STOCK));
    }

    // ── incrementStock ────────────────────────────────────────────────────────

    @Test
    @DisplayName("incrementStock: 정상 증가 시 재고가 amount만큼 늘어난다")
    void incrementStock_whenValidAmount_increasesStock() {
        // given
        int initialStock = product.getStock();
        int amount = 50;

        // when
        product.incrementStock(amount);

        // then
        assertThat(product.getStock()).isEqualTo(initialStock + amount);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -5, Integer.MIN_VALUE})
    @DisplayName("incrementStock: 음수 amount 입력 시 NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK 예외")
    void incrementStock_whenNegativeAmount_throwsProductStockException(int negativeAmount) {
        // when & then
        assertThatThrownBy(() -> product.incrementStock(negativeAmount))
                .isInstanceOf(ProductStockException.class)
                .satisfies(ex -> assertThat(((ProductStockException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK));
    }
}
