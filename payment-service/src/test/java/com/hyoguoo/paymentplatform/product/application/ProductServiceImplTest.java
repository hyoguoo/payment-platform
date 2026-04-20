package com.hyoguoo.paymentplatform.product.application;

import com.hyoguoo.paymentplatform.mock.FakeProductRepository;
import com.hyoguoo.paymentplatform.product.application.dto.ProductStockCommand;
import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.exception.ProductFoundException;
import com.hyoguoo.paymentplatform.product.exception.ProductStockException;
import com.hyoguoo.paymentplatform.product.presentation.port.ProductService;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProductServiceImplTest {

    private ProductService productService;
    private FakeProductRepository fakeProductRepository;

    @BeforeEach
    void setUp() {
        fakeProductRepository = new FakeProductRepository();
        productService = new ProductServiceImpl(fakeProductRepository);
    }

    @Test
    @DisplayName("유효한 ID로 제품을 조회하면 제품을 반환한다.")
    void getById_ProductFound() {
        // given
        Long saveId = 1L;
        Product product = Product.allArgsBuilder()
                .id(saveId)
                .name("테스트 상품 1")
                .description("테스트 상품 1입니다.")
                .stock(100)
                .sellerId(1L)
                .allArgsBuild();
        fakeProductRepository.saveOrUpdate(product);

        // when
        Product foundProduct = productService.getById(saveId);

        // then
        Assertions.assertThat(foundProduct.getId()).isEqualTo(saveId);
    }

    @Test
    @DisplayName("유효하지 않은 ID로 제품을 조회하면 예외를 던진다.")
    void getById_ProductNotFound() {
        // given
        Long saveId = 1L;
        Product product = Product.allArgsBuilder()
                .id(saveId)
                .name("테스트 상품 1")
                .description("테스트 상품 1입니다.")
                .stock(100)
                .sellerId(1L)
                .allArgsBuild();
        fakeProductRepository.saveOrUpdate(product);
        Long invalidProductId = 999L;

        // when & then
        Assertions.assertThatThrownBy(() -> productService.getById(invalidProductId))
                .isInstanceOf(ProductFoundException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "1, 100, 10, 90, 2, 200, 50, 150",
            "1, 100, 100, 0, 2, 200, 200, 0",
            "1, 59, 50, 9, 2, 99, 50, 49"
    })
    @DisplayName("여러 제품의 재고를 감소시키면 수량이 감소된다.")
    void decreaseStockForOrders_Success(
            Long productId1, int initialStock1, int reduceStock1, int expectedStock1,
            Long productId2, int initialStock2, int reduceStock2, int expectedStock2
    ) {
        // given
        Product product1 = Product.allArgsBuilder()
                .id(productId1)
                .name("테스트 상품 1")
                .description("테스트 상품 1입니다.")
                .stock(initialStock1)
                .sellerId(1L)
                .allArgsBuild();
        Product product2 = Product.allArgsBuilder()
                .id(productId2)
                .name("테스트 상품 2")
                .description("테스트 상품 2입니다.")
                .stock(initialStock2)
                .sellerId(1L)
                .allArgsBuild();
        fakeProductRepository.saveOrUpdate(product1);
        fakeProductRepository.saveOrUpdate(product2);

        List<ProductStockCommand> productStockCommandList = List.of(
                ProductStockCommand.builder().productId(productId1).stock(reduceStock1).build(),
                ProductStockCommand.builder().productId(productId2).stock(reduceStock2).build()
        );

        // when
        productService.decreaseStockForOrders(productStockCommandList);

        // then
        Product updatedProduct1 = productService.getById(productId1);
        Product updatedProduct2 = productService.getById(productId2);
        Assertions.assertThat(updatedProduct1.getStock()).isEqualTo(expectedStock1);
        Assertions.assertThat(updatedProduct2.getStock()).isEqualTo(expectedStock2);
    }

    @ParameterizedTest
    @CsvSource({
            "1, 100, 150",
            "2, 200, 300"
    })
    @DisplayName("재고가 부족할 경우 예외를 던진지고, 재고는 변하지 않는다.")
    void decreaseStockForOrders_NotEnoughStock(Long productId, int initialStock, int reduceStock) {
        // given
        Product product = Product.allArgsBuilder()
                .id(productId)
                .name("테스트 상품 1")
                .description("테스트 상품 1입니다.")
                .stock(initialStock)
                .sellerId(1L)
                .allArgsBuild();
        fakeProductRepository.saveOrUpdate(product);

        List<ProductStockCommand> productStockCommandList = List.of(
                ProductStockCommand.builder().productId(productId).stock(reduceStock).build()
        );

        // when & then
        Assertions.assertThatThrownBy(
                        () -> productService.decreaseStockForOrders(productStockCommandList)
                )
                .isInstanceOf(ProductStockException.class);

        Product unchangedProduct = productService.getById(productId);
        Assertions.assertThat(unchangedProduct.getStock()).isEqualTo(initialStock);
    }

    @ParameterizedTest
    @CsvSource({
            "1, 100, -10",
            "1, 200, -20"
    })
    @DisplayName("재고 감소 수가 음수일 경우 예외를 던지고, 재고는 변하지 않는다.")
    void decreaseStockForOrders_NegativeStock(Long productId, int initialStock, int reduceStock) {
        // given
        Product product = Product.allArgsBuilder()
                .id(productId)
                .name("테스트 상품")
                .description("테스트 상품입니다.")
                .stock(initialStock)
                .sellerId(1L)
                .allArgsBuild();
        fakeProductRepository.saveOrUpdate(product);

        List<ProductStockCommand> productStockCommandList = List.of(
                ProductStockCommand.builder().productId(productId).stock(reduceStock).build()
        );

        // when & then
        Assertions.assertThatThrownBy(
                        () -> productService.decreaseStockForOrders(productStockCommandList)
                )
                .isInstanceOf(ProductStockException.class);

        Product unchangedProduct = productService.getById(productId);
        Assertions.assertThat(unchangedProduct.getStock()).isEqualTo(initialStock);
    }

    @ParameterizedTest
    @CsvSource({
            "1, 100, 20, 120, 2, 200, 30, 230",
            "1, 50, 10, 60, 2, 150, 20, 170"
    })
    @DisplayName("여러 제품의 재고를 증가시키면 수량이 증가된다.")
    void increaseStockForOrders_Success(
            Long productId1, int initialStock1, int increaseStock1, int expectedStock1,
            Long productId2, int initialStock2, int increaseStock2, int expectedStock2
    ) {
        // given
        Product product1 = Product.allArgsBuilder()
                .id(productId1)
                .name("테스트 상품 1")
                .description("테스트 상품 1입니다.")
                .stock(initialStock1)
                .sellerId(1L)
                .allArgsBuild();
        Product product2 = Product.allArgsBuilder()
                .id(productId2)
                .name("테스트 상품 2")
                .description("테스트 상품 2입니다.")
                .stock(initialStock2)
                .sellerId(1L)
                .allArgsBuild();
        fakeProductRepository.saveOrUpdate(product1);
        fakeProductRepository.saveOrUpdate(product2);

        List<ProductStockCommand> productStockCommandList = List.of(
                ProductStockCommand.builder().productId(productId1).stock(increaseStock1).build(),
                ProductStockCommand.builder().productId(productId2).stock(increaseStock2).build()
        );

        // when
        productService.increaseStockForOrders(productStockCommandList);

        // then
        Product updatedProduct1 = productService.getById(productId1);
        Product updatedProduct2 = productService.getById(productId2);
        Assertions.assertThat(updatedProduct1.getStock()).isEqualTo(expectedStock1);
        Assertions.assertThat(updatedProduct2.getStock()).isEqualTo(expectedStock2);
    }

    @ParameterizedTest
    @CsvSource({
            "1, 100, -20",
            "1, 200, -30"
    })
    @DisplayName("재고 증가 수가 음수일 경우 예외를 던지고, 재고는 변하지 않는다.")
    void increaseStockForOrders_NegativeStock(Long productId, int initialStock, int increaseStock) {
        // given
        Product product = Product.allArgsBuilder()
                .id(productId)
                .name("테스트 상품")
                .description("테스트 상품입니다.")
                .stock(initialStock)
                .sellerId(1L)
                .allArgsBuild();
        fakeProductRepository.saveOrUpdate(product);

        List<ProductStockCommand> productStockCommandList = List.of(
                ProductStockCommand.builder().productId(productId).stock(increaseStock).build()
        );

        // when & then
        Assertions.assertThatThrownBy(
                        () -> productService.increaseStockForOrders(productStockCommandList)
                )
                .isInstanceOf(ProductStockException.class);

        Product unchangedProduct = productService.getById(productId);
        Assertions.assertThat(unchangedProduct.getStock()).isEqualTo(initialStock);
    }
}
