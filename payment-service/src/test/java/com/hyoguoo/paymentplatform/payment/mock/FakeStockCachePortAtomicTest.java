package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCompensationAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockDecrementAtomicResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FakeStockCachePortAtomicTest {

    private FakeStockCachePort fakeStockCachePort;

    @BeforeEach
    void setUp() {
        fakeStockCachePort = new FakeStockCachePort();
    }

    @Test
    void decrementAtomic_정상_차감_성공() {
        // given
        fakeStockCachePort.set(1L, 10);
        fakeStockCachePort.set(2L, 5);
        List<PaymentOrder> orders = List.of(
                buildOrder(1L, 3),
                buildOrder(2L, 2)
        );

        // when
        StockDecrementAtomicResult result = fakeStockCachePort.decrementAtomic("order-001", orders);

        // then
        assertThat(result).isEqualTo(StockDecrementAtomicResult.OK);
        assertThat(fakeStockCachePort.current(1L)).isEqualTo(7);
        assertThat(fakeStockCachePort.current(2L)).isEqualTo(3);
    }

    @Test
    void decrementAtomic_재고_부족() {
        // given
        fakeStockCachePort.set(1L, 0);
        List<PaymentOrder> orders = List.of(buildOrder(1L, 1));

        // when
        StockDecrementAtomicResult result = fakeStockCachePort.decrementAtomic("order-002", orders);

        // then
        assertThat(result).isEqualTo(StockDecrementAtomicResult.INSUFFICIENT);
        assertThat(fakeStockCachePort.current(1L)).isEqualTo(0);
    }

    @Test
    void decrementAtomic_중복_orderId() {
        // given
        fakeStockCachePort.set(1L, 10);
        List<PaymentOrder> orders = List.of(buildOrder(1L, 3));
        fakeStockCachePort.decrementAtomic("order-003", orders);

        // when
        StockDecrementAtomicResult result = fakeStockCachePort.decrementAtomic("order-003", orders);

        // then
        assertThat(result).isEqualTo(StockDecrementAtomicResult.ALREADY_DONE);
    }

    @Test
    void compensateAtomic_정상_보상() {
        // given
        fakeStockCachePort.set(1L, 5);
        fakeStockCachePort.set(2L, 3);
        List<PaymentOrder> orders = List.of(
                buildOrder(1L, 2),
                buildOrder(2L, 1)
        );

        // when
        StockCompensationAtomicResult result = fakeStockCachePort.compensateAtomic("order-004", orders);

        // then
        assertThat(result).isEqualTo(StockCompensationAtomicResult.OK);
        assertThat(fakeStockCachePort.current(1L)).isEqualTo(7);
        assertThat(fakeStockCachePort.current(2L)).isEqualTo(4);
    }

    @Test
    void compensateAtomic_중복_orderId() {
        // given
        fakeStockCachePort.set(1L, 5);
        List<PaymentOrder> orders = List.of(buildOrder(1L, 2));
        fakeStockCachePort.compensateAtomic("order-005", orders);

        // when
        StockCompensationAtomicResult result = fakeStockCachePort.compensateAtomic("order-005", orders);

        // then
        assertThat(result).isEqualTo(StockCompensationAtomicResult.ALREADY_DONE);
        assertThat(fakeStockCachePort.current(1L)).isEqualTo(7); // 첫 번째 보상 결과만 반영
    }

    // --- helpers ---

    private PaymentOrder buildOrder(Long productId, int quantity) {
        return PaymentOrder.allArgsBuilder()
                .id(productId)
                .paymentEventId(1L)
                .orderId("order-test")
                .productId(productId)
                .quantity(quantity)
                .totalAmount(BigDecimal.valueOf(1000))
                .status(PaymentOrderStatus.EXECUTING)
                .allArgsBuild();
    }
}
