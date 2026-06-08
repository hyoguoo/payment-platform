package com.hyoguoo.paymentplatform.product.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.hyoguoo.paymentplatform.product.application.port.out.ProductRepository;
import com.hyoguoo.paymentplatform.product.domain.Product;
import com.hyoguoo.paymentplatform.product.exception.ProductNotFoundException;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("ProductQueryUseCase 테스트")
@ExtendWith(MockitoExtension.class)
class ProductQueryUseCaseTest {

    @InjectMocks
    private ProductQueryUseCase sut;

    @Mock
    private ProductRepository productRepository;

    @Test
    @DisplayName("getById: 상품이 존재하면 Product 도메인 객체를 반환한다")
    void getById_whenProductExists_returnsProduct() {
        // given
        long productId = 1L;
        Product product = Product.allArgsBuilder()
                .id(productId)
                .name("테스트 상품")
                .price(BigDecimal.valueOf(10000))
                .description("상품 설명")
                .stock(50)
                .sellerId(1L)
                .allArgsBuild();
        given(productRepository.findById(productId)).willReturn(Optional.of(product));

        // when
        Product result = sut.getById(productId);

        // then
        assertThat(result.getId()).isEqualTo(productId);
        assertThat(result.getName()).isEqualTo("테스트 상품");
        assertThat(result.getStock()).isEqualTo(50);
    }

    @Test
    @DisplayName("getById: 상품이 존재하지 않으면 ProductNotFoundException을 던진다")
    void getById_whenProductNotFound_throwsProductNotFoundException() {
        // given
        long productId = 999L;
        given(productRepository.findById(productId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sut.getById(productId))
                .isInstanceOf(ProductNotFoundException.class);
    }
}
