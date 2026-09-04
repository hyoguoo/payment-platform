package com.hyoguoo.paymentplatform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentStatusResult.StatusType;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentStatusQueryPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentStatusSnapshot;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentStatusQueryPort;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

@DisplayName("PaymentStatusServiceImpl 테스트")
class PaymentStatusServiceImplTest {

    private static final String ORDER_ID = "order-1";

    private PaymentStatusServiceImpl paymentStatusService;
    private FakePaymentStatusQueryPort paymentStatusQueryPort;

    @BeforeEach
    void setUp() {
        paymentStatusQueryPort = new FakePaymentStatusQueryPort();
        paymentStatusService = new PaymentStatusServiceImpl(paymentStatusQueryPort);
    }

    @Test
    @DisplayName("발행 대기 상태면 PENDING을 반환한다")
    void getPaymentStatus_발행대기면_PENDING() {
        // given
        paymentStatusQueryPort.putActiveOutboxStatus(ORDER_ID, PaymentOutboxStatus.PENDING);

        // when
        PaymentStatusResult result = paymentStatusService.getPaymentStatus(ORDER_ID);

        // then
        assertThat(result.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(result.getStatus()).isEqualTo(StatusType.PENDING);
        assertThat(result.getApprovedAt()).isNull();
    }

    @Test
    @DisplayName("발행 중 상태면 PROCESSING을 반환한다")
    void getPaymentStatus_발행중이면_PROCESSING() {
        // given
        paymentStatusQueryPort.putActiveOutboxStatus(ORDER_ID, PaymentOutboxStatus.IN_FLIGHT);

        // when
        PaymentStatusResult result = paymentStatusService.getPaymentStatus(ORDER_ID);

        // then
        assertThat(result.getStatus()).isEqualTo(StatusType.PROCESSING);
        assertThat(result.getApprovedAt()).isNull();
    }

    @Test
    @DisplayName("발행 기록이 없고 이벤트가 DONE이면 DONE과 승인시각을 반환한다")
    void getPaymentStatus_발행기록없고_이벤트가_DONE이면_DONE과_승인시각() {
        // given
        Instant approvedAt = Instant.parse("2026-03-18T12:00:00Z");
        paymentStatusQueryPort.putStatusSnapshot(
                ORDER_ID, new PaymentStatusSnapshot(ORDER_ID, PaymentEventStatus.DONE, approvedAt));

        // when
        PaymentStatusResult result = paymentStatusService.getPaymentStatus(ORDER_ID);

        // then
        assertThat(result.getStatus()).isEqualTo(StatusType.DONE);
        assertThat(result.getApprovedAt()).isEqualTo(approvedAt);
    }

    @Test
    @DisplayName("발행 기록이 없고 이벤트가 FAILED면 FAILED를 반환한다")
    void getPaymentStatus_발행기록없고_이벤트가_FAILED면_FAILED() {
        // given
        paymentStatusQueryPort.putStatusSnapshot(
                ORDER_ID, new PaymentStatusSnapshot(ORDER_ID, PaymentEventStatus.FAILED, null));

        // when
        PaymentStatusResult result = paymentStatusService.getPaymentStatus(ORDER_ID);

        // then
        assertThat(result.getStatus()).isEqualTo(StatusType.FAILED);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"DONE", "FAILED"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("발행 기록이 없고 이벤트가 DONE/FAILED가 아니면 PROCESSING을 반환한다")
    void getPaymentStatus_발행기록없고_이벤트가_진행중이면_PROCESSING(PaymentEventStatus status) {
        // given
        paymentStatusQueryPort.putStatusSnapshot(
                ORDER_ID, new PaymentStatusSnapshot(ORDER_ID, status, null));

        // when
        PaymentStatusResult result = paymentStatusService.getPaymentStatus(ORDER_ID);

        // then
        assertThat(result.getStatus()).isEqualTo(StatusType.PROCESSING);
    }

    @Test
    @DisplayName("발행 기록도 이벤트도 없으면 PAYMENT_EVENT_NOT_FOUND 예외를 던진다")
    void getPaymentStatus_이벤트가_없으면_PAYMENT_EVENT_NOT_FOUND() {
        // when & then
        assertThatThrownBy(() -> paymentStatusService.getPaymentStatus(ORDER_ID))
                .isInstanceOf(PaymentFoundException.class);
    }

    @Test
    @DisplayName("발행 기록이 있으면 스냅샷을 읽지 않는다")
    void getPaymentStatus_발행기록이_있으면_스냅샷을_읽지_않는다() {
        // given
        paymentStatusQueryPort.putActiveOutboxStatus(ORDER_ID, PaymentOutboxStatus.PENDING);
        PaymentStatusQueryPort spiedPort = Mockito.spy(paymentStatusQueryPort);
        paymentStatusService = new PaymentStatusServiceImpl(spiedPort);

        // when
        paymentStatusService.getPaymentStatus(ORDER_ID);

        // then
        verify(spiedPort, never()).findStatusSnapshot(ORDER_ID);
    }
}
