package com.hyoguoo.paymentplatform.payment.application.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentConfirmAsyncResultTest {

    @Test
    @DisplayName("SYNC_200 ResponseType으로 빌드 시 responseType이 SYNC_200이다")
    void build_WithSync200_HasSync200ResponseType() {
        PaymentConfirmAsyncResult result = PaymentConfirmAsyncResult.builder()
                .responseType(PaymentConfirmAsyncResult.ResponseType.SYNC_200)
                .orderId("order-001")
                .amount(new BigDecimal("15000"))
                .build();

        assertThat(result.getResponseType()).isEqualTo(PaymentConfirmAsyncResult.ResponseType.SYNC_200);
        assertThat(result.getOrderId()).isEqualTo("order-001");
        assertThat(result.getAmount()).isEqualByComparingTo(new BigDecimal("15000"));
    }

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
                .responseType(PaymentConfirmAsyncResult.ResponseType.SYNC_200)
                .orderId("order-003")
                .amount(null)
                .build();

        assertThat(result.getAmount()).isNull();
    }

    @Test
    @DisplayName("ResponseType enum은 SYNC_200과 ASYNC_202 두 값을 갖는다")
    void responseType_HasTwoValues() {
        PaymentConfirmAsyncResult.ResponseType[] values = PaymentConfirmAsyncResult.ResponseType.values();

        assertThat(values).containsExactlyInAnyOrder(
                PaymentConfirmAsyncResult.ResponseType.SYNC_200,
                PaymentConfirmAsyncResult.ResponseType.ASYNC_202
        );
    }
}
