package com.hyoguoo.paymentplatform.payment.application.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentConfirmAsyncResultTest {

    @Test
    @DisplayName("ASYNC_202 ResponseType으로 빌드 시 responseType이 ASYNC_202이다")
    void build_WithAsync202_HasAsync202ResponseType() {
        PaymentConfirmAsyncResult result = PaymentConfirmAsyncResult.builder()
                .responseType(PaymentConfirmAsyncResult.ResponseType.ASYNC_202)
                .orderId("order-002")
                .amount(null)
                .build();

        assertThat(result.getResponseType()).isEqualTo(PaymentConfirmAsyncResult.ResponseType.ASYNC_202);
        assertThat(result.getOrderId()).isEqualTo("order-002");
        assertThat(result.getAmount()).isNull();
    }

    @Test
    @DisplayName("amount 필드는 null을 허용한다")
    void build_WithNullAmount_IsAllowed() {
        PaymentConfirmAsyncResult result = PaymentConfirmAsyncResult.builder()
                .responseType(PaymentConfirmAsyncResult.ResponseType.ASYNC_202)
                .orderId("order-003")
                .amount(null)
                .build();

        assertThat(result.getAmount()).isNull();
    }

    @Test
    @DisplayName("ResponseType enum은 ASYNC_202 값만 갖는다")
    void responseType_HasOnlyAsync202() {
        PaymentConfirmAsyncResult.ResponseType[] values = PaymentConfirmAsyncResult.ResponseType.values();

        assertThat(values).containsExactly(PaymentConfirmAsyncResult.ResponseType.ASYNC_202);
    }

    @Test
    @DisplayName("orderId와 amount가 정상적으로 빌드된다")
    void build_WithOrderIdAndAmount_FieldsAreSet() {
        PaymentConfirmAsyncResult result = PaymentConfirmAsyncResult.builder()
                .responseType(PaymentConfirmAsyncResult.ResponseType.ASYNC_202)
                .orderId("order-001")
                .amount(new BigDecimal("15000"))
                .build();

        assertThat(result.getOrderId()).isEqualTo("order-001");
        assertThat(result.getAmount()).isEqualByComparingTo(new BigDecimal("15000"));
    }
}
