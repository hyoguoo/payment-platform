package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.payment.application.port.out.ProductPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class StockResyncUseCaseTest {

    private StockResyncUseCase stockResyncUseCase;
    private ProductPort mockProductPort;
    private StockCachePort mockStockCachePort;

    @BeforeEach
    void setUp() {
        mockProductPort = Mockito.mock(ProductPort.class);
        mockStockCachePort = Mockito.mock(StockCachePort.class);
        stockResyncUseCase = new StockResyncUseCase(mockProductPort, mockStockCachePort);
    }

    @Test
    @DisplayName("resyncStockCache — product RDB(SoT) 재고로 redis-stock 캐시를 덮어쓰고 그 수량을 반환한다")
    void resyncStockCache_setsRedisToRdbStock() {
        // given — product RDB 가 재고 42 를 보고한다
        when(mockProductPort.getProductInfoById(1L))
                .thenReturn(ProductInfo.builder().id(1L).stock(42).build());

        // when
        int result = stockResyncUseCase.resyncStockCache(1L);

        // then — 캐시를 RDB 값으로 set 하고 그 수량을 반환
        assertThat(result).isEqualTo(42);
        verify(mockStockCachePort).set(1L, 42);
    }
}
