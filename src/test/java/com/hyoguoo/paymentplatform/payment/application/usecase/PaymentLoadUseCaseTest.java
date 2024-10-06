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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentLoadUseCaseTest {

    private PaymentLoadUseCase paymentLoadUseCase;
    private FakePaymentEventRepository fakePaymentEventRepository;
    private TestLocalDateTimeProvider testLocalDateTimeProvider;

    static Stream<Arguments> provideRetryablePaymentEvents() {
        return Stream.of(
                // IN_PROGRESS면서 시간이 지나지 않은 경우
                Arguments.of(
                        PaymentEventStatus.IN_PROGRESS,
                        PaymentEvent.RETRYABLE_MINUTES_FOR_IN_PROGRESS - 1,
                        false
                ),
                // IN_PROGRESS면서 시간이 지난 경우
                Arguments.of(
                        PaymentEventStatus.IN_PROGRESS,
                        PaymentEvent.RETRYABLE_MINUTES_FOR_IN_PROGRESS + 1,
                        true
                ),
                // UNKNOWN면서 시간이 지나지 않은 경우
                Arguments.of(
                        PaymentEventStatus.UNKNOWN,
                        PaymentEvent.RETRYABLE_MINUTES_FOR_IN_PROGRESS - 1,
                        true
                ),
                // UNKNOWN면서 시간이 지난 경우
                Arguments.of(
                        PaymentEventStatus.UNKNOWN,
                        PaymentEvent.RETRYABLE_MINUTES_FOR_IN_PROGRESS + 1,
                        true
                )
        );
    }

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

    @ParameterizedTest
    @MethodSource("provideRetryablePaymentEvents")
    @DisplayName("결제 재시도 이벤트 조회 시 특정 시간 이후의 IN_PROGRESS 혹은 UNKNOWN 상태인 이벤트를 조회한다.")
    void testGetRetryablePaymentEvents_Success(PaymentEventStatus status, int offsetMinutes, boolean isRetryable) {
        // given
        LocalDateTime now = LocalDateTime.of(2021, 1, 1, 0, 0);
        ReflectionTestUtils.invokeMethod(testLocalDateTimeProvider, "setFixedDateTime", now);

        LocalDateTime executedAt = now.minusMinutes(offsetMinutes);

        PaymentEvent paymentEvent = PaymentEvent.allArgsBuilder()
                .status(status)
                .executedAt(executedAt)
                .retryCount(0)
                .paymentOrderList(new ArrayList<>())
                .allArgsBuild();

        fakePaymentEventRepository.saveOrUpdate(paymentEvent);

        // when
        List<PaymentEvent> retryablePaymentEventList = paymentLoadUseCase.getRetryablePaymentEvents();

        // then
        assertThat(retryablePaymentEventList.contains(paymentEvent)).isEqualTo(isRetryable);
    }
}
