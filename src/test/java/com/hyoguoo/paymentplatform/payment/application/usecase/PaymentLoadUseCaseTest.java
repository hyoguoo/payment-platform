package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyoguoo.paymentplatform.mock.FakePaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentLoadUseCaseTest {

    private PaymentLoadUseCase paymentLoadUseCase;
    private FakePaymentEventRepository fakePaymentEventRepository;

    @BeforeEach
    void setUp() {
        fakePaymentEventRepository = new FakePaymentEventRepository();
        paymentLoadUseCase = new PaymentLoadUseCase(fakePaymentEventRepository);
    }

    @Test
    @DisplayName("OrderId로 PaymentEvent를 조회한다.")
    void testGetPaymentEventByOrderId_Success() {
        // given
        String orderId = "order123";
        PaymentEvent paymentEvent = PaymentEvent.allArgsBuilder()
                .orderId(orderId)
                .paymentOrderList(new ArrayList<>())
                .allArgsBuild();
        fakePaymentEventRepository.saveOrUpdate(paymentEvent);

        // when
        PaymentEvent foundPaymentEvent = paymentLoadUseCase.getPaymentEventByOrderId(orderId);

        // then
        assertThat(foundPaymentEvent.getOrderId()).isEqualTo(paymentEvent.getOrderId());
    }

    @Test
    @DisplayName("존재하지 않는 PaymentEvent를 조회할 때 예외를 던진다.")
    void testFindAndExecutePayment_NotFound() {
        // given
        String orderId = "order123";

        // when & then
        assertThatThrownBy(() ->
                paymentLoadUseCase.getPaymentEventByOrderId(orderId))
                .isInstanceOf(PaymentFoundException.class);
    }
}
