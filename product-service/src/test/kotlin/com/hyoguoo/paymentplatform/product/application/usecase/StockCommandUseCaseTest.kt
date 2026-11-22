package com.hyoguoo.paymentplatform.product.application.usecase

import com.hyoguoo.paymentplatform.product.application.dto.ProductStockCommand
import com.hyoguoo.paymentplatform.product.application.port.out.StockRepository
import com.hyoguoo.paymentplatform.product.domain.Stock
import com.hyoguoo.paymentplatform.product.exception.ProductNotFoundException
import com.hyoguoo.paymentplatform.product.exception.ProductStockException
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Optional

@DisplayName("StockCommandUseCase 테스트")
class StockCommandUseCaseTest {
    private lateinit var stockRepository: StockRepository
    private lateinit var sut: StockCommandUseCase

    @BeforeEach
    fun setUp() {
        stockRepository = mockk()
        sut = StockCommandUseCase(stockRepository)
    }

    @Test
    @DisplayName("decreaseForOrders: 단건 정상 차감 시 감소된 수량으로 save가 호출된다")
    fun decreaseForOrders_whenValidSingleCommand_savesDecreasedStock() {
        // given
        val productId = 1L
        val currentQuantity = 50
        val decreaseAmount = 10

        val currentStock = Stock(productId, currentQuantity)
        every { stockRepository.findByProductId(productId) } returns Optional.of(currentStock)
        val captor = slot<Stock>()
        every { stockRepository.save(capture(captor)) } answers { firstArg() }

        val commands = listOf(ProductStockCommand(productId, decreaseAmount))

        // when
        sut.decreaseForOrders(commands)

        // then
        verify(exactly = 1) { stockRepository.save(any()) }
        assertThat(captor.captured.productId).isEqualTo(productId)
        assertThat(captor.captured.quantity).isEqualTo(currentQuantity - decreaseAmount)
    }

    @Test
    @DisplayName("decreaseForOrders: 다건 정상 차감 시 각 상품에 대해 save가 호출된다")
    fun decreaseForOrders_whenMultipleValidCommands_savesEachDecreasedStock() {
        // given
        val productId1 = 1L
        val productId2 = 2L
        val currentQuantity1 = 30
        val currentQuantity2 = 20
        val decreaseAmount1 = 5
        val decreaseAmount2 = 8

        every { stockRepository.findByProductId(productId1) } returns
            Optional.of(Stock(productId1, currentQuantity1))
        every { stockRepository.findByProductId(productId2) } returns
            Optional.of(Stock(productId2, currentQuantity2))
        val savedStocks = mutableListOf<Stock>()
        every { stockRepository.save(capture(savedStocks)) } answers { firstArg() }

        val commands =
            listOf(
                ProductStockCommand(productId1, decreaseAmount1),
                ProductStockCommand(productId2, decreaseAmount2),
            )

        // when
        sut.decreaseForOrders(commands)

        // then
        verify(exactly = 2) { stockRepository.save(any()) }

        assertThat(savedStocks).anyMatch { s ->
            s.productId == productId1 && s.quantity == currentQuantity1 - decreaseAmount1
        }
        assertThat(savedStocks).anyMatch { s ->
            s.productId == productId2 && s.quantity == currentQuantity2 - decreaseAmount2
        }
    }

    @Test
    @DisplayName("decreaseForOrders: 재고 부족 시 NOT_ENOUGH_STOCK 예외 발생, save 미호출")
    fun decreaseForOrders_whenStockInsufficient_throwsNotEnoughStockAndNoSave() {
        // given
        val productId = 1L
        val currentQuantity = 5
        val decreaseAmount = 10 // 재고(5)보다 많음

        val currentStock = Stock(productId, currentQuantity)
        every { stockRepository.findByProductId(productId) } returns Optional.of(currentStock)

        val commands = listOf(ProductStockCommand(productId, decreaseAmount))

        // when & then
        assertThatThrownBy { sut.decreaseForOrders(commands) }
            .isInstanceOfSatisfying(ProductStockException::class.java) { ex ->
                assertThat(ex.errorCode).isEqualTo(ProductErrorCode.NOT_ENOUGH_STOCK)
            }

        verify(exactly = 0) { stockRepository.save(any()) }
    }

    @Test
    @DisplayName("decreaseForOrders: 상품 없음 시 ProductNotFoundException 예외 발생, save 미호출")
    fun decreaseForOrders_whenProductNotFound_throwsProductNotFoundException() {
        // given
        val productId = 99L
        every { stockRepository.findByProductId(productId) } returns Optional.empty()

        val commands = listOf(ProductStockCommand(productId, 5))

        // when & then
        assertThatThrownBy { sut.decreaseForOrders(commands) }
            .isInstanceOfSatisfying(ProductNotFoundException::class.java) { ex ->
                assertThat(ex.errorCode).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND)
            }

        verify(exactly = 0) { stockRepository.save(any()) }
    }
}
