package com.hyoguoo.paymentplatform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import java.math.BigDecimal;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentOrderTest {

    private static PaymentOrder getDefaultPaymentOrderWithStatus(PaymentOrderStatus initialStatus) {
        return PaymentOrder.allArgsBuilder()
                .id(1L)
                .paymentEventId(2L)
                .orderId("order123")
                .productId(3L)
                .quantity(2)
                .totalAmount(new BigDecimal("10000"))
                .status(initialStatus)
                .allArgsBuild();
    }

    @Test
    @DisplayName("allArgs Builder를 사용하여 PaymentOrder 객체를 생성한다.")
    void testAllArgsConstructor_Success() {
        // given
        Long id = 1L;
        Long paymentEventId = 1L;
        Long productId = 1L;
        String orderId = "order123";
        Integer quantity = 2;
        BigDecimal totalAmount = new BigDecimal("10000");
        PaymentOrderStatus status = PaymentOrderStatus.NOT_STARTED;

        // when
        PaymentOrder paymentOrder = PaymentOrder.allArgsBuilder()
                .id(id)
                .paymentEventId(paymentEventId)
                .orderId(orderId)
                .productId(productId)
                .quantity(quantity)
                .totalAmount(totalAmount)
                .status(status)
                .allArgsBuild();

        // then
        Assertions.assertThat(paymentOrder)
                .extracting(PaymentOrder::getId,
                        PaymentOrder::getPaymentEventId,
                        PaymentOrder::getOrderId,
                        PaymentOrder::getProductId,
                        PaymentOrder::getQuantity,
                        PaymentOrder::getTotalAmount,
                        PaymentOrder::getStatus)
                .containsExactly(id, paymentEventId, orderId, productId, quantity, totalAmount,
                        status);
    }

    @ParameterizedTest
    @CsvSource({"10000, 2, 20000", "5000, 3, 15000", "1000, 5, 5000"})
    @DisplayName("required Builder를 사용하여 PaymentOrder 객체를 생성 시 올바른 totalAmount와 NOT_STARTED 상태로 생성한다.")
    void testRequiredConstructor_Success(BigDecimal price, int quantity,
            BigDecimal expectedTotalAmount) {
        // given
        PaymentEvent paymentEvent = PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(1L)
                .sellerId(2L)
                .orderName("order name")
                .orderId("orderId")
                .paymentKey("paymentKey")
                .status(PaymentEventStatus.READY)
                .allArgsBuild();
        OrderedProduct orderedProduct = OrderedProduct.builder()
                .productId(1L)
                .quantity(quantity)
                .build();
        ProductInfo productInfo = ProductInfo.builder()
                .id(1L)
                .name("Product 1")
                .price(price)
                .stock(10)
                .sellerId(1L)
                .build();

        // when
        PaymentOrder paymentOrder = PaymentOrder.requiredBuilder()
                .paymentEvent(paymentEvent)
                .orderedProduct(orderedProduct)
                .productInfo(productInfo)
                .requiredBuild();

        // then
        assertThat(paymentOrder.getStatus()).isEqualTo(PaymentOrderStatus.NOT_STARTED);
        assertThat(paymentOrder.getTotalAmount()).isEqualTo(expectedTotalAmount);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentOrderStatus.class, names = {"NOT_STARTED", "EXECUTING", "UNKNOWN"})
    @DisplayName("특정 상태에서 성공적으로 execute 상태로 변경한다.")
    void execute_Success(PaymentOrderStatus initialStatus) {
        // given
        PaymentOrder paymentOrder = getDefaultPaymentOrderWithStatus(initialStatus);

        // when
        paymentOrder.execute();

        // then
        assertThat(paymentOrder.getStatus()).isEqualTo(PaymentOrderStatus.EXECUTING);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentOrderStatus.class, names = {"SUCCESS", "FAIL", "CANCEL"})
    @DisplayName("execute 상태로 변경 불가한 상태에서는 예외를 던진다.")
    void execute_InvalidStatus(PaymentOrderStatus initialStatus) {
        // given
        PaymentOrder paymentOrder = getDefaultPaymentOrderWithStatus(initialStatus);

        // when & then
        assertThatThrownBy(paymentOrder::execute)
                .isInstanceOf(PaymentStatusException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentOrderStatus.class, names = {"EXECUTING", "UNKNOWN"})
    @DisplayName("특정 상태에서 성공적으로 fail 상태로 변경한다.")
    void fail_Success(PaymentOrderStatus initialStatus) {
        // given
        PaymentOrder paymentOrder = getDefaultPaymentOrderWithStatus(initialStatus);

        // when
        paymentOrder.fail();

        // then
        assertThat(paymentOrder.getStatus()).isEqualTo(PaymentOrderStatus.FAIL);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentOrderStatus.class, names = {"NOT_STARTED", "SUCCESS", "CANCEL"})
    @DisplayName("fail 상태로 변경 불가한 상태에서는 예외를 던진다.")
    void fail_InvalidStatus(PaymentOrderStatus initialStatus) {
        // given
        PaymentOrder paymentOrder = getDefaultPaymentOrderWithStatus(initialStatus);

        // when & then
        assertThatThrownBy(paymentOrder::fail)
                .isInstanceOf(PaymentStatusException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentOrderStatus.class, names = {"EXECUTING", "UNKNOWN"})
    @DisplayName("특정 상태에서 성공적으로 success 상태로 변경한다.")
    void success_Success(PaymentOrderStatus initialStatus) {
        // given
        PaymentOrder paymentOrder = getDefaultPaymentOrderWithStatus(initialStatus);

        // when
        paymentOrder.success();

        // then
        assertThat(paymentOrder.getStatus()).isEqualTo(PaymentOrderStatus.SUCCESS);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentOrderStatus.class, names = {"NOT_STARTED", "FAIL", "CANCEL"})
    @DisplayName("success 상태로 변경 불가한 상태에서는 예외를 던진다.")
    void success_InvalidStatus(PaymentOrderStatus initialStatus) {
        // given
        PaymentOrder paymentOrder = getDefaultPaymentOrderWithStatus(initialStatus);

        // when & then
        assertThatThrownBy(paymentOrder::success)
                .isInstanceOf(PaymentStatusException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentOrderStatus.class, names = {"NOT_STARTED", "EXECUTING"})
    @DisplayName("특정 상태에서 성공적으로 unknown 상태로 변경한다.")
    void unknown_Success(PaymentOrderStatus initialStatus) {
        // given
        PaymentOrder paymentOrder = getDefaultPaymentOrderWithStatus(initialStatus);

        // when
        paymentOrder.unknown();

        // then
        assertThat(paymentOrder.getStatus()).isEqualTo(PaymentOrderStatus.UNKNOWN);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentOrderStatus.class, names = {"SUCCESS", "FAIL", "CANCEL"})
    @DisplayName("unknown 상태로 변경 불가한 상태에서는 예외를 던진다.")
    void unknown_InvalidStatus(PaymentOrderStatus initialStatus) {
        // given
        PaymentOrder paymentOrder = getDefaultPaymentOrderWithStatus(initialStatus);

        // when & then
        assertThatThrownBy(paymentOrder::unknown)
                .isInstanceOf(PaymentStatusException.class);
    }
}
