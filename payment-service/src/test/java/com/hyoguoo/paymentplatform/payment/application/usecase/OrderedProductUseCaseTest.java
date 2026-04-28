package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductPort;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OrderedProductUseCaseTest {

    private OrderedProductUseCase orderedProductUseCase;
    private ProductPort mockProductPort;

    @BeforeEach
    void setUp() {
        mockProductPort = Mockito.mock(ProductPort.class);
        orderedProductUseCase = new OrderedProductUseCase(mockProductPort);
    }

    @Test
    @DisplayName("OrderedProduct를 기반으로 상품 정보를 조회하여 ProductInfo 리스트를 반환한다.")
    void testGetProductInfoList_Success() {
        // given
        OrderedProduct orderedProduct1 = OrderedProduct.builder().productId(1L).build();
        OrderedProduct orderedProduct2 = OrderedProduct.builder().productId(2L).build();

        List<OrderedProduct> orderedProductList = List.of(orderedProduct1, orderedProduct2);

        ProductInfo productInfo1 = ProductInfo.builder()
                .id(1L)
                .name("Product 1")
                .price(new BigDecimal("10000"))
                .stock(50)
                .sellerId(1L)
                .build();

        ProductInfo productInfo2 = ProductInfo.builder()
                .id(2L)
                .name("Product 2")
                .price(new BigDecimal("20000"))
                .stock(30)
                .sellerId(1L)
                .build();


        // when
        when(mockProductPort.getProductInfoById(1L)).thenReturn(productInfo1);
        when(mockProductPort.getProductInfoById(2L)).thenReturn(productInfo2);
        List<ProductInfo> productInfoList = orderedProductUseCase.getProductInfoList(
                orderedProductList
        );

        // then
        assertThat(productInfoList).hasSize(2);
        assertThat(productInfoList.get(0).getName()).isEqualTo("Product 1");
        assertThat(productInfoList.get(1).getName()).isEqualTo("Product 2");

        verify(mockProductPort, times(1)).getProductInfoById(1L);
        verify(mockProductPort, times(1)).getProductInfoById(2L);
    }
}
