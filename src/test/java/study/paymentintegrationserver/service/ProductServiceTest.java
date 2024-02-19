package study.paymentintegrationserver.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import study.paymentintegrationserver.entity.Product;
import study.paymentintegrationserver.exception.ProductErrorMessage;
import study.paymentintegrationserver.exception.ProductException;
import study.paymentintegrationserver.repository.ProductRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatExceptionOfType;
import static org.mockito.Mockito.when;
import static study.paymentintegrationserver.TestDataFactory.generateProductWithPriceAndStock;


class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("상품 조회 시 상품이 존재하면 상품을 반환합니다.")
    void getProductByIdSuccess() {
        // Given
        Long productId = 1L;
        Product expectedProduct = generateProductWithPriceAndStock(BigDecimal.valueOf(1000), 10);

        when(productRepository.findById(productId)).thenReturn(Optional.of(expectedProduct));

        // When
        Product result = productService.getById(productId);

        // Then
        assertThat(result).isEqualTo(expectedProduct);
    }

    @Test
    @DisplayName("상품 조회 시 상품이 존재하지 않으면 예외를 발생시킵니다.")
    void getProductNotFound() {
        // Given
        Long productId = 1L;

        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        // When, Then
        assertThatThrownBy(() -> productService.getById(productId))
                .isInstanceOf(ProductException.class)
                .hasMessageContaining(ProductErrorMessage.NOT_FOUND.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
            "10, 5, 5",
            "10, 10, 0",
            "10, 1, 9"
    })
    @DisplayName("상품 재고 차감 시 재고가 차감된 상품을 반환합니다.")
    void reduceStockSuccess(Integer initialStock, Integer reduceStock, Integer expectedStock) {
        // Given
        Long productId = 1L;

        Product product = generateProductWithPriceAndStock(BigDecimal.valueOf(1000), initialStock);

        when(productRepository.findByIdPessimistic(productId)).thenReturn(Optional.of(product));

        // When
        Product result = productService.reduceStockWithCommit(productId, reduceStock);

        // Assert
        assertThat(product.getId()).isEqualTo(result.getId());
        assertThat(result.getStock()).isEqualTo(expectedStock);
    }

    @ParameterizedTest
    @CsvSource({
            "10, 11",
            "10, 100",
            "10, 1000"
    })
    @DisplayName("상품 재고 차감 시 재고가 0보다 작으면 예외를 발생시킵니다.")
    void reduceStockFail(Integer initialStock, Integer reduceStock) {
        // Given
        Long productId = 1L;

        Product product = generateProductWithPriceAndStock(BigDecimal.valueOf(1000), initialStock);

        when(productRepository.findByIdPessimistic(productId)).thenReturn(Optional.of(product));

        // When, Then
        assertThatExceptionOfType(ProductException.class)
                .isThrownBy(() -> productService.reduceStockWithCommit(productId, reduceStock))
                .withMessageContaining(ProductErrorMessage.NOT_ENOUGH_STOCK.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
            "10, 5, 15",
            "10, 10, 20",
            "10, 1, 11"
    })
    @DisplayName("상품 재고 증가 시 재고가 증가된 상품을 반환합니다.")
    void increaseStockSuccess(Integer initialStock, Integer increaseStock, Integer expectedStock) {
        // Given
        Long productId = 1L;

        Product product = generateProductWithPriceAndStock(BigDecimal.valueOf(1000), initialStock);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        // When
        Product result = productService.increaseStock(productId, increaseStock);

        // Then
        assertThat(product.getId()).isEqualTo(result.getId());
        assertThat(result.getStock()).isEqualTo(expectedStock);
    }
}
