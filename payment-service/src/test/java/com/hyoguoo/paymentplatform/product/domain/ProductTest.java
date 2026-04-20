package com.hyoguoo.paymentplatform.product.domain;

import com.hyoguoo.paymentplatform.product.exception.ProductStockException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProductTest {

    @Test
    @DisplayName("allArgs Builder를 사용하여 Product 객체를 생성한다.")
    void createProduct_AllArgsBuilder() {
        // given
        Long id = 1L;
        String name = "테스트 상품";
        String description = "테스트 상품입니다.";
        int stock = 100;
        Long sellerId = 1L;

        // when
        Product product = Product.allArgsBuilder()
                .id(id)
                .name(name)
                .description(description)
                .stock(stock)
                .sellerId(sellerId)
                .allArgsBuild();

        // then
        Assertions.assertThat(product)
                .extracting(Product::getId,
                        Product::getName,
                        Product::getDescription,
                        Product::getStock,
                        Product::getSellerId)
                .containsExactly(id, name, description, stock, sellerId);
    }

    @ParameterizedTest
    @CsvSource({"100, 10, 90", "100, 100, 0", "59, 50, 9"})
    @DisplayName("현재 재고에서 주어진 수량만큼 재고를 감소시킨다.")
    void decrementStock_Success(int initialStock, int reduceStock, int expectedStock) {
        // given
        Product product = Product.allArgsBuilder()
                .id(1L)
                .name("테스트 상품")
                .description("테스트 상품입니다.")
                .stock(initialStock)
                .sellerId(1L)
                .allArgsBuild();

        // when
        product.decrementStock(reduceStock);

        // then
        Assertions.assertThat(product.getStock()).isEqualTo(expectedStock);
    }

    @ParameterizedTest
    @CsvSource({"3, -3", "1, -1"})
    @DisplayName("재고 감소 시 음수를 입력하면 예외를 던지고, 재고는 변하지 않는다.")
    void decrementStock_NegativeNumber(int initialStock, int reduceStock) {
        // given
        Product product = Product.allArgsBuilder()
                .id(1L)
                .name("테스트 상품")
                .description("테스트 상품입니다.")
                .stock(initialStock)
                .sellerId(1L)
                .allArgsBuild();

        // when & then
        Assertions.assertThatThrownBy(() -> product.decrementStock(reduceStock))
                .isInstanceOf(ProductStockException.class);

        Assertions.assertThat(product.getStock()).isEqualTo(initialStock);
    }

    @ParameterizedTest
    @CsvSource({"100, 101", "59, 60", "0, 1"})
    @DisplayName("재고 감소 시 재고가 부족하면 예외를 던지고, 재고는 변하지 않는다.")
    void decrementStock_NotEnoughStock(int initialStock, int reduceStock) {
        // given
        Product product = Product.allArgsBuilder()
                .id(1L)
                .name("테스트 상품")
                .description("테스트 상품입니당.")
                .stock(initialStock)
                .sellerId(1L)
                .allArgsBuild();

        // when & then
        Assertions.assertThatThrownBy(() -> product.decrementStock(reduceStock))
                .isInstanceOf(ProductStockException.class);

        Assertions.assertThat(product.getStock()).isEqualTo(initialStock);
    }

    @ParameterizedTest
    @CsvSource({"100, 10, 110", "100, 100, 200", "59, 50, 109"})
    @DisplayName("현재 재고에서 주어진 수량만큼 재고를 증가시킨다.")
    void incrementStock_Success(int initialStock, int increaseStock, int expectedStock) {
        // given
        Product product = Product.allArgsBuilder()
                .id(1L)
                .name("테스트 상품")
                .description("테스트 상품입니다.")
                .stock(initialStock)
                .sellerId(1L)
                .allArgsBuild();

        // when
        product.incrementStock(increaseStock);

        // then
        Assertions.assertThat(product.getStock()).isEqualTo(expectedStock);
    }

    @ParameterizedTest
    @CsvSource({"3, -3", "1, -1"})
    @DisplayName("재고 증가 시 음수를 입력하면 예외를 던지고, 재고는 변하지 않는다.")
    void incrementStock_NegativeNumber(int initialStock, int increaseStock) {
        // given
        Product product = Product.allArgsBuilder()
                .id(1L)
                .name("테스트 상품")
                .description("테스트 상품입니다.")
                .stock(initialStock)
                .sellerId(1L)
                .allArgsBuild();

        // when & then
        Assertions.assertThatThrownBy(() -> product.incrementStock(increaseStock))
                .isInstanceOf(ProductStockException.class);

        Assertions.assertThat(product.getStock()).isEqualTo(initialStock);
    }
}
