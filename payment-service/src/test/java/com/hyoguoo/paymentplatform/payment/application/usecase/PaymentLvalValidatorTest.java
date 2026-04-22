package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentValidException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentLvalValidatorTest {

    private PaymentLvalValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PaymentLvalValidator();
    }

    @Test
    @DisplayName("요청 금액이 PaymentEvent의 총 금액과 일치하면 예외 없이 통과한다")
    void validate_WhenAmountMatches_ShouldPass() {
        // given
        PaymentEvent event = buildPaymentEventWithAmount(BigDecimal.valueOf(10_000));

        // when & then
        assertThatCode(() -> validator.validate(event, BigDecimal.valueOf(10_000)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("요청 금액이 PaymentEvent의 총 금액과 불일치하면 4xx 예외로 거부한다")
    void validate_WhenAmountMismatches_ShouldReject4xx() {
        // given
        PaymentEvent event = buildPaymentEventWithAmount(BigDecimal.valueOf(10_000));

        // when & then
        assertThatThrownBy(() -> validator.validate(event, BigDecimal.valueOf(9_999)))
                .isInstanceOf(PaymentValidException.class);
    }

    private PaymentEvent buildPaymentEventWithAmount(BigDecimal amount) {
        PaymentOrder order = PaymentOrder.allArgsBuilder()
                .id(1L)
                .paymentEventId(1L)
                .orderId("order-test-001")
                .productId(100L)
                .quantity(1)
                .totalAmount(amount)
                .status(PaymentOrderStatus.NOT_STARTED)
                .allArgsBuild();

        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(1L)
                .sellerId(1L)
                .orderName("테스트 주문")
                .orderId("order-test-001")
                .paymentKey(null)
                .gatewayType(PaymentGatewayType.TOSS)
                .status(PaymentEventStatus.READY)
                .executedAt(null)
                .approvedAt(null)
                .retryCount(0)
                .statusReason(null)
                .paymentOrderList(List.of(order))
                .createdAt(LocalDateTime.now())
                .lastStatusChangedAt(LocalDateTime.now())
                .quarantineCompensationPending(false)
                .allArgsBuild();
    }
}
