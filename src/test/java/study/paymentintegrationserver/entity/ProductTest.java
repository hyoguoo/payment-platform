package study.paymentintegrationserver.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import study.paymentintegrationserver.exception.ProductErrorMessage;
import study.paymentintegrationserver.exception.ProductException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static study.paymentintegrationserver.TestDataFactory.generateProductWithPriceAndStock;

class ProductTest {

    @ParameterizedTest
    @CsvSource({
            "10, 5, 5",
            "20, 10, 10"
    })
    @DisplayName("재고 감소 시 재고가 감소된다.")
    void reduceStock(Integer initialStock, Integer reduceStockAmount, Integer expectedStock) {
        // Given
        Product product = generateProductWithPriceAndStock(BigDecimal.valueOf(10000), initialStock);

        // When
        product.reduceStock(reduceStockAmount);

        // Then
        assertThat(product.getStock()).isEqualTo(expectedStock);
    }

    @ParameterizedTest
    @CsvSource({
            "10, 11",
            "20, 21"
    })
    @DisplayName("감소되는 재고가 재고보다 많으면 예외가 발생한다.")
    void reduceStockFail(Integer initialStock, Integer reduceStockAmount) {
        // Given
        Product product = generateProductWithPriceAndStock(BigDecimal.valueOf(10000), initialStock);

        // When
        // Then
        assertThatThrownBy(() -> product.reduceStock(reduceStockAmount))
                .isInstanceOf(ProductException.class)
                .hasMessageContaining(ProductErrorMessage.NOT_ENOUGH_STOCK.getMessage());
    }


    @ParameterizedTest
    @CsvSource({
            "10, 5, 15",
            "20, 10, 30"
    })
    @DisplayName("재고 증가 시 재고가 증가된다.")
    void increaseStock(Integer initialStock, Integer increaseStockAmount, Integer expectedStock) {
        // Given
        Product product = generateProductWithPriceAndStock(BigDecimal.valueOf(10000), initialStock);

        // When
        product.increaseStock(increaseStockAmount);

        // Then
        assertThat(product.getStock()).isEqualTo(expectedStock);
    }

    @ParameterizedTest
    @CsvSource({
            "-1",
            "-10"
    })
    @DisplayName("증가하거나 감소되는 갯수가 음수면 예외가 발생한다.")
    void negativeIncreaseOrReduceStock(Integer increaseStockAmount) {
        // Given
        Product product = generateProductWithPriceAndStock(BigDecimal.valueOf(10000), 10);

        // When, Then
        assertThatThrownBy(() -> product.increaseStock(increaseStockAmount))
                .isInstanceOf(ProductException.class)
                .hasMessageContaining(ProductErrorMessage.NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK.getMessage());
        assertThatThrownBy(() -> product.reduceStock(increaseStockAmount))
                .isInstanceOf(ProductException.class)
                .hasMessageContaining(ProductErrorMessage.NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
            "10, 5",
            "20, 10"
    })
    @DisplayName("재고가 충분하면 예외가 발생하지 않는다.")
    void validateStock(Integer initialStock, Integer validateStockAmount) {
        // Given
        Product product = generateProductWithPriceAndStock(BigDecimal.valueOf(10000), initialStock);

        // When/Then
        product.validateStock(validateStockAmount); // Should not throw an exception
    }

    @ParameterizedTest
    @CsvSource({
            "-1",
            "-10"
    })
    @DisplayName("재고 검증 시 검증할 재고가 음수면 예외가 발생한다.")
    void validateStockNegative(Integer validateStockAmount) {
        // Given
        Product product = generateProductWithPriceAndStock(BigDecimal.valueOf(10000), 10);

        // When, Then
        assertThatThrownBy(() -> product.validateStock(validateStockAmount))
                .isInstanceOf(ProductException.class)
                .hasMessageContaining(ProductErrorMessage.NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
            "10, 15",
            "20, 21"
    })
    @DisplayName("재고가 충분하지 않으면 예외가 발생한다.")
    void validateStockFail(Integer initialStock, Integer validateStockAmount) {
        // Given
        Product product = generateProductWithPriceAndStock(BigDecimal.valueOf(10000), initialStock);

        // When
        // Then
        assertThatThrownBy(() -> product.validateStock(validateStockAmount))
                .isInstanceOf(ProductException.class)
                .hasMessageContaining(ProductErrorMessage.NOT_ENOUGH_STOCK.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
            "10, 5, 50",
            "20, 10, 200"
    })
    @DisplayName("총 가격을 계산한다.")
    void calculateTotalPrice(Integer quantity, Integer price, Integer expectedTotalPrice) {
        // Given
        Product product = generateProductWithPriceAndStock(BigDecimal.valueOf(price), 10);

        // When
        BigDecimal totalPrice = product.calculateTotalPrice(quantity);

        // Then
        assertThat(totalPrice).isEqualTo(BigDecimal.valueOf(expectedTotalPrice));
    }
}
