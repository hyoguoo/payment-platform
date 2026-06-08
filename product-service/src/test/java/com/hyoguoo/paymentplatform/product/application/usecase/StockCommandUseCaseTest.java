package com.hyoguoo.paymentplatform.product.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hyoguoo.paymentplatform.product.application.dto.ProductStockCommand;
import com.hyoguoo.paymentplatform.product.application.port.out.StockRepository;
import com.hyoguoo.paymentplatform.product.domain.Stock;
import com.hyoguoo.paymentplatform.product.exception.ProductNotFoundException;
import com.hyoguoo.paymentplatform.product.exception.ProductStockException;
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("StockCommandUseCase 테스트")
@ExtendWith(MockitoExtension.class)
class StockCommandUseCaseTest {

    @InjectMocks
    private StockCommandUseCase sut;

    @Mock
    private StockRepository stockRepository;

    @Test
    @DisplayName("decreaseForOrders: 단건 정상 차감 시 감소된 수량으로 save가 호출된다")
    void decreaseForOrders_whenValidSingleCommand_savesDecreasedStock() {
        // given
        long productId = 1L;
        int currentQuantity = 50;
        int decreaseAmount = 10;

        Stock currentStock = Stock.allArgsBuilder()
                .productId(productId)
                .quantity(currentQuantity)
                .allArgsBuild();
        given(stockRepository.findByProductId(productId)).willReturn(Optional.of(currentStock));

        List<ProductStockCommand> commands = List.of(new ProductStockCommand(productId, decreaseAmount));

        // when
        sut.decreaseForOrders(commands);

        // then
        ArgumentCaptor<Stock> captor = forClass(Stock.class);
        verify(stockRepository).save(captor.capture());
        assertThat(captor.getValue().getProductId()).isEqualTo(productId);
        assertThat(captor.getValue().getQuantity()).isEqualTo(currentQuantity - decreaseAmount);
    }

    @Test
    @DisplayName("decreaseForOrders: 다건 정상 차감 시 각 상품에 대해 save가 호출된다")
    void decreaseForOrders_whenMultipleValidCommands_savesEachDecreasedStock() {
        // given
        long productId1 = 1L;
        long productId2 = 2L;
        int currentQuantity1 = 30;
        int currentQuantity2 = 20;
        int decreaseAmount1 = 5;
        int decreaseAmount2 = 8;

        given(stockRepository.findByProductId(productId1)).willReturn(Optional.of(
                Stock.allArgsBuilder().productId(productId1).quantity(currentQuantity1).allArgsBuild()));
        given(stockRepository.findByProductId(productId2)).willReturn(Optional.of(
                Stock.allArgsBuilder().productId(productId2).quantity(currentQuantity2).allArgsBuild()));

        List<ProductStockCommand> commands = List.of(
                new ProductStockCommand(productId1, decreaseAmount1),
                new ProductStockCommand(productId2, decreaseAmount2)
        );

        // when
        sut.decreaseForOrders(commands);

        // then
        ArgumentCaptor<Stock> captor = forClass(Stock.class);
        verify(stockRepository, org.mockito.Mockito.times(2)).save(captor.capture());

        List<Stock> savedStocks = captor.getAllValues();
        assertThat(savedStocks).anyMatch(s ->
                s.getProductId().equals(productId1) && s.getQuantity() == currentQuantity1 - decreaseAmount1);
        assertThat(savedStocks).anyMatch(s ->
                s.getProductId().equals(productId2) && s.getQuantity() == currentQuantity2 - decreaseAmount2);
    }

    @Test
    @DisplayName("decreaseForOrders: 재고 부족 시 NOT_ENOUGH_STOCK 예외 발생, save 미호출")
    void decreaseForOrders_whenStockInsufficient_throwsNotEnoughStockAndNoSave() {
        // given
        long productId = 1L;
        int currentQuantity = 5;
        int decreaseAmount = 10; // 재고(5)보다 많음

        Stock currentStock = Stock.allArgsBuilder()
                .productId(productId)
                .quantity(currentQuantity)
                .allArgsBuild();
        given(stockRepository.findByProductId(productId)).willReturn(Optional.of(currentStock));

        List<ProductStockCommand> commands = List.of(new ProductStockCommand(productId, decreaseAmount));

        // when & then
        assertThatThrownBy(() -> sut.decreaseForOrders(commands))
                .isInstanceOf(ProductStockException.class)
                .satisfies(ex -> assertThat(((ProductStockException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.NOT_ENOUGH_STOCK));

        verify(stockRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("decreaseForOrders: 상품 없음 시 ProductNotFoundException 예외 발생, save 미호출")
    void decreaseForOrders_whenProductNotFound_throwsProductNotFoundException() {
        // given
        long productId = 99L;
        given(stockRepository.findByProductId(productId)).willReturn(Optional.empty());

        List<ProductStockCommand> commands = List.of(new ProductStockCommand(productId, 5));

        // when & then
        assertThatThrownBy(() -> sut.decreaseForOrders(commands))
                .isInstanceOf(ProductNotFoundException.class)
                .satisfies(ex -> assertThat(((ProductNotFoundException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));

        verify(stockRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
