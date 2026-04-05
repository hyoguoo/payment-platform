package com.hyoguoo.paymentplatform.payment.application.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentConfirmAsyncResultTest {

    @Test
    @DisplayName("orderId와 amount가 정상적으로 빌드된다")
    void build_WithOrderIdAndAmount_FieldsAreSet() {
        PaymentConfirmAsyncResult result = PaymentConfirmAsyncResult.builder()
                .orderId("order-001")
                .amount(new BigDecimal("15000"))
                .build();

        assertThat(result.getOrderId()).isEqualTo("order-001");
        assertThat(result.getAmount()).isEqualByComparingTo(new BigDecimal("15000"));
    }

    @Test
    @DisplayName("amount 필드는 null을 허용한다")
    void build_WithNullAmount_IsAllowed() {
        PaymentConfirmAsyncResult result = PaymentConfirmAsyncResult.builder()
                .orderId("order-002")
                .amount(null)
                .build();

        assertThat(result.getAmount()).isNull();
    }

    @Test
    @DisplayName("queueNearFull 기본값은 false다")
    void build_Default_QueueNearFullIsFalse() {
        PaymentConfirmAsyncResult result = PaymentConfirmAsyncResult.builder()
                .orderId("order-003")
                .amount(BigDecimal.valueOf(10000))
                .build();

        assertThat(result.isQueueNearFull()).isFalse();
    }

    @Test
    @DisplayName("queueNearFull=true로 빌드 시 isQueueNearFull()이 true다")
    void build_WithQueueNearFull_IsTrue() {
        PaymentConfirmAsyncResult result = PaymentConfirmAsyncResult.builder()
                .orderId("order-004")
                .amount(BigDecimal.valueOf(10000))
                .queueNearFull(true)
                .build();

        assertThat(result.isQueueNearFull()).isTrue();
    }
}
