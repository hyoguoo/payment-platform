package com.hyoguoo.paymentplatform.payment.infrastructure.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.exception.UnsupportedPaymentGatewayException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentGatewayFactoryTest {

    private PaymentGatewayFactory factory;

    @BeforeEach
    void setUp() {
        List<PaymentGatewayStrategy> strategies = List.of(
                new TestTossPaymentGatewayStrategy()
        );
        factory = new PaymentGatewayFactory(strategies);
    }

    @Test
    @DisplayName("TOSS 타입 요청 시 TossPaymentGatewayStrategy 반환")
    void getStrategy_WithTossType_ReturnsTossStrategy() {
        // given
        PaymentGatewayType type = PaymentGatewayType.TOSS;

        // when
        PaymentGatewayStrategy strategy = factory.getStrategy(type);

        // then
        assertThat(strategy).isInstanceOf(TestTossPaymentGatewayStrategy.class);
        assertThat(strategy.supports(PaymentGatewayType.TOSS)).isTrue();
    }

    @Test
    @DisplayName("지원하지 않는 PG 타입 요청 시 UnsupportedPaymentGatewayException 발생")
    void getStrategy_WithUnsupportedType_ThrowsException() {
        // given

        // when & then
        assertThatThrownBy(() -> factory.getStrategy(null))
                .isInstanceOf(UnsupportedPaymentGatewayException.class);
    }

    @Test
    @DisplayName("전략 목록이 비어있을 때 예외 발생")
    void getStrategy_WithEmptyStrategies_ThrowsException() {
        // given
        PaymentGatewayFactory emptyFactory = new PaymentGatewayFactory(List.of());

        // when & then
        assertThatThrownBy(() -> emptyFactory.getStrategy(PaymentGatewayType.TOSS))
                .isInstanceOf(UnsupportedPaymentGatewayException.class);
    }

    // Test implementation of PaymentGatewayStrategy
    static class TestTossPaymentGatewayStrategy implements PaymentGatewayStrategy {

        @Override
        public boolean supports(PaymentGatewayType type) {
            return type == PaymentGatewayType.TOSS;
        }

        @Override
        public PaymentConfirmResult confirm(PaymentConfirmRequest request) {
            return null;
        }

        @Override
        public PaymentCancelResult cancel(PaymentCancelRequest request) {
            return null;
        }

        @Override
        public PaymentStatusResult getStatus(String paymentKey) {
            return null;
        }
    }
}
