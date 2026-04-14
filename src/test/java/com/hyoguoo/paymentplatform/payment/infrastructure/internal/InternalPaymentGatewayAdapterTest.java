package com.hyoguoo.paymentplatform.payment.infrastructure.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.infrastructure.gateway.PaymentGatewayFactory;
import com.hyoguoo.paymentplatform.payment.infrastructure.gateway.PaymentGatewayProperties;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayRetryableException;
import com.hyoguoo.paymentplatform.payment.infrastructure.gateway.PaymentGatewayStrategy;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InternalPaymentGatewayAdapterTest {

    @Mock
    private PaymentGatewayFactory factory;

    @Mock
    private PaymentGatewayProperties properties;

    @Mock
    private PaymentGatewayStrategy tossStrategy;

    @Mock
    private PaymentGatewayStrategy nicepayStrategy;

    private InternalPaymentGatewayAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new InternalPaymentGatewayAdapter(factory, properties);
    }

    @Test
    @DisplayName("confirm — TOSS gatewayType이면 Toss 전략의 confirm()을 호출한다")
    void confirm_TossGatewayType_CallsTossStrategy()
            throws PaymentGatewayRetryableException, PaymentGatewayNonRetryableException {
        PaymentConfirmRequest request = new PaymentConfirmRequest(
                "order-001", "payKey-toss", BigDecimal.valueOf(10000), PaymentGatewayType.TOSS
        );
        PaymentConfirmResult expectedResult = new PaymentConfirmResult(
                PaymentConfirmResultStatus.SUCCESS, "payKey-toss", "order-001",
                BigDecimal.valueOf(10000), null, null
        );

        when(factory.getStrategy(PaymentGatewayType.TOSS)).thenReturn(tossStrategy);
        when(tossStrategy.confirm(request)).thenReturn(expectedResult);

        PaymentConfirmResult result = adapter.confirm(request);

        verify(factory).getStrategy(PaymentGatewayType.TOSS);
        verify(tossStrategy).confirm(request);
        assertThat(result).isEqualTo(expectedResult);
    }

    @Test
    @DisplayName("confirm — NICEPAY gatewayType이면 NicePay 전략의 confirm()을 호출한다")
    void confirm_NicepayGatewayType_CallsNicepayStrategy()
            throws PaymentGatewayRetryableException, PaymentGatewayNonRetryableException {
        PaymentConfirmRequest request = new PaymentConfirmRequest(
                "order-002", "payKey-nicepay", BigDecimal.valueOf(20000), PaymentGatewayType.NICEPAY
        );
        PaymentConfirmResult expectedResult = new PaymentConfirmResult(
                PaymentConfirmResultStatus.SUCCESS, "payKey-nicepay", "order-002",
                BigDecimal.valueOf(20000), null, null
        );

        when(factory.getStrategy(PaymentGatewayType.NICEPAY)).thenReturn(nicepayStrategy);
        when(nicepayStrategy.confirm(request)).thenReturn(expectedResult);

        PaymentConfirmResult result = adapter.confirm(request);

        verify(factory).getStrategy(PaymentGatewayType.NICEPAY);
        verify(nicepayStrategy).confirm(request);
        assertThat(result).isEqualTo(expectedResult);
    }

    @Test
    @DisplayName("getStatusByOrderId — NICEPAY gatewayType이면 NicePay 전략의 getStatusByOrderId()를 호출한다")
    void getStatusByOrderId_NicepayGatewayType_CallsNicepayStrategy()
            throws PaymentGatewayRetryableException, PaymentGatewayNonRetryableException {
        String orderId = "order-003";
        PaymentStatusResult expectedResult = new PaymentStatusResult(
                "payKey-nicepay", orderId, PaymentStatus.DONE,
                BigDecimal.valueOf(15000), null, null
        );

        when(factory.getStrategy(PaymentGatewayType.NICEPAY)).thenReturn(nicepayStrategy);
        when(nicepayStrategy.getStatusByOrderId(orderId))
                .thenReturn(expectedResult);

        PaymentStatusResult result = adapter.getStatusByOrderId(orderId, PaymentGatewayType.NICEPAY);

        verify(factory).getStrategy(PaymentGatewayType.NICEPAY);
        verify(nicepayStrategy).getStatusByOrderId(orderId);
        assertThat(result).isEqualTo(expectedResult);
    }
}
