package com.hyoguoo.paymentplatform.payment.mock;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FakeStockHoldRecordRepository — countNoiseByProductId 가 JPA 어댑터와 같은 시맨틱을 내는지 확인.
 */
@DisplayName("FakeStockHoldRecordRepository — countNoiseByProductId")
class FakeStockHoldRecordRepositoryTest {

    private static final Long PRODUCT_ID = 501L;
    private static final Long OTHER_PRODUCT_ID = 502L;
    private static final Integer QUANTITY = 3;

    private final FakeStockHoldRecordRepository sut = new FakeStockHoldRecordRepository();

    @Test
    @DisplayName("그 상품의 잡음 기록만 센다")
    void countNoiseByProductId_그_상품의_잡음_기록만_센다() {
        // given — 대상 상품의 잡음 2건 + 확정 1건 + 되돌림 1건, 그리고 다른 상품의 잡음 1건
        sut.openHold("order-fake-cnp-noise-1", product("order-fake-cnp-noise-1", PRODUCT_ID));
        sut.openHold("order-fake-cnp-noise-2", product("order-fake-cnp-noise-2", PRODUCT_ID));

        String committedOrderId = "order-fake-cnp-committed";
        sut.openHold(committedOrderId, product(committedOrderId, PRODUCT_ID));
        sut.commitAllByOrderId(committedOrderId);

        String revertedOrderId = "order-fake-cnp-reverted";
        String revertedCycleToken = sut.openHold(revertedOrderId, product(revertedOrderId, PRODUCT_ID));
        sut.closeAsReverted(revertedOrderId, product(revertedOrderId, PRODUCT_ID), revertedCycleToken);

        String otherProductOrderId = "order-fake-cnp-other-product";
        sut.openHold(otherProductOrderId, product(otherProductOrderId, OTHER_PRODUCT_ID));

        // when
        long count = sut.countNoiseByProductId(PRODUCT_ID);

        // then
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("기록이 없으면 0을 반환한다")
    void countNoiseByProductId_기록이_없으면_0을_반환한다() {
        // when
        long count = sut.countNoiseByProductId(PRODUCT_ID);

        // then
        assertThat(count).isZero();
    }

    private PaymentOrder product(String orderId, Long productId) {
        return PaymentOrder.allArgsBuilder()
                .orderId(orderId)
                .productId(productId)
                .quantity(QUANTITY)
                .totalAmount(BigDecimal.valueOf(1_000))
                .allArgsBuild();
    }
}
