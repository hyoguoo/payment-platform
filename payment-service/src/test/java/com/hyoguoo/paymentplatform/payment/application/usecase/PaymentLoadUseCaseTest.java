package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyoguoo.paymentplatform.mock.FakePaymentEventRepository;
import com.hyoguoo.paymentplatform.mock.TestLocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentLoadUseCaseTest {

    private PaymentLoadUseCase paymentLoadUseCase;
    private FakePaymentEventRepository fakePaymentEventRepository;
    private TestLocalDateTimeProvider testLocalDateTimeProvider;

    @BeforeEach
    void setUp() {
        fakePaymentEventRepository = new FakePaymentEventRepository();
        testLocalDateTimeProvider = new TestLocalDateTimeProvider();
        paymentLoadUseCase = new PaymentLoadUseCase(fakePaymentEventRepository, testLocalDateTimeProvider);
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

    @Test
    @DisplayName("EXPIRATION_MINUTES 보다 오래된 READY 결제만 만료 후보로 반환한다.")
    void testGetReadyPaymentsOlder_returnsExpiredReady() {
        // given
        LocalDateTime baseNow = LocalDateTime.of(2026, 1, 1, 12, 0);
        testLocalDateTimeProvider.setFixedDateTime(baseNow);
        LocalDateTime expiredAt = baseNow.minusMinutes(PaymentEvent.EXPIRATION_MINUTES + 1);
        PaymentEvent expired = PaymentEvent.allArgsBuilder()
                .orderId("expired-order")
                .status(PaymentEventStatus.READY)
                .createdAt(expiredAt)
                .paymentOrderList(new ArrayList<>())
                .allArgsBuild();
        fakePaymentEventRepository.saveOrUpdate(expired);

        // when
        List<PaymentEvent> result = paymentLoadUseCase.getReadyPaymentsOlder();

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOrderId()).isEqualTo("expired-order");
    }

    @Test
    @DisplayName("EXPIRATION_MINUTES 이내에 생성된 READY 결제는 만료 후보에서 제외된다.")
    void testGetReadyPaymentsOlder_excludesFreshReady() {
        // given
        LocalDateTime baseNow = LocalDateTime.of(2026, 1, 1, 12, 0);
        testLocalDateTimeProvider.setFixedDateTime(baseNow);
        LocalDateTime freshAt = baseNow.minusMinutes(PaymentEvent.EXPIRATION_MINUTES - 1);
        PaymentEvent fresh = PaymentEvent.allArgsBuilder()
                .orderId("fresh-order")
                .status(PaymentEventStatus.READY)
                .createdAt(freshAt)
                .paymentOrderList(new ArrayList<>())
                .allArgsBuild();
        fakePaymentEventRepository.saveOrUpdate(fresh);

        // when
        List<PaymentEvent> result = paymentLoadUseCase.getReadyPaymentsOlder();

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("READY 외 상태(IN_PROGRESS 등)의 결제는 오래되었더라도 제외된다.")
    void testGetReadyPaymentsOlder_excludesNonReady() {
        // given
        LocalDateTime baseNow = LocalDateTime.of(2026, 1, 1, 12, 0);
        testLocalDateTimeProvider.setFixedDateTime(baseNow);
        LocalDateTime longAgo = baseNow.minusMinutes(PaymentEvent.EXPIRATION_MINUTES + 60);
        PaymentEvent inProgress = PaymentEvent.allArgsBuilder()
                .orderId("in-progress-order")
                .status(PaymentEventStatus.IN_PROGRESS)
                .createdAt(longAgo)
                .paymentOrderList(new ArrayList<>())
                .allArgsBuild();
        fakePaymentEventRepository.saveOrUpdate(inProgress);

        // when
        List<PaymentEvent> result = paymentLoadUseCase.getReadyPaymentsOlder();

        // then
        assertThat(result).isEmpty();
    }

}
