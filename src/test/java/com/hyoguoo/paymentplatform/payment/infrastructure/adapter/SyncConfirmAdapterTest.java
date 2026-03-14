package com.hyoguoo.paymentplatform.payment.infrastructure.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.payment.application.PaymentConfirmServiceImpl;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmAsyncResult.ResponseType;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentConfirmResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

class SyncConfirmAdapterTest {

    private PaymentConfirmServiceImpl paymentConfirmServiceImpl;
    private SyncConfirmAdapter syncConfirmAdapter;

    @BeforeEach
    void setUp() {
        paymentConfirmServiceImpl = mock(PaymentConfirmServiceImpl.class);
        syncConfirmAdapter = new SyncConfirmAdapter(paymentConfirmServiceImpl);
    }

    @Test
    @DisplayName("confirm() 호출 시 PaymentConfirmServiceImpl에 위임하고 ResponseType.SYNC_200과 동일한 orderId/amount를 반환한다.")
    void confirm_success() {
        // given
        PaymentConfirmCommand command = PaymentConfirmCommand.builder()
                .userId(1L)
                .orderId("order-1")
                .paymentKey("payment-key-1")
                .amount(BigDecimal.valueOf(1000))
                .build();

        PaymentConfirmResult mockResult = PaymentConfirmResult.builder()
                .orderId("order-1")
                .amount(BigDecimal.valueOf(1000))
                .build();

        when(paymentConfirmServiceImpl.confirm(command)).thenReturn(mockResult);

        // when
        PaymentConfirmAsyncResult result = syncConfirmAdapter.confirm(command);

        // then
        assertThat(result.getResponseType()).isEqualTo(ResponseType.SYNC_200);
        assertThat(result.getOrderId()).isEqualTo("order-1");
        assertThat(result.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(1000));
    }

    @Test
    @DisplayName("SyncConfirmAdapter에 @ConditionalOnProperty(name=spring.payment.async-strategy, havingValue=sync, matchIfMissing=true)가 선언되어 있다.")
    void conditional_property() {
        // given
        ConditionalOnProperty annotation = SyncConfirmAdapter.class
                .getAnnotation(ConditionalOnProperty.class);

        // then
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).containsExactly("spring.payment.async-strategy");
        assertThat(annotation.havingValue()).isEqualTo("sync");
        assertThat(annotation.matchIfMissing()).isTrue();
    }
}
