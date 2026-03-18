package com.hyoguoo.paymentplatform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentStatusResult.StatusType;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("PaymentStatusServiceImpl 테스트")
class PaymentStatusServiceImplTest {

    private PaymentStatusServiceImpl paymentStatusService;

    private PaymentLoadUseCase mockPaymentLoadUseCase;
    private PaymentOutboxUseCase mockPaymentOutboxUseCase;

    @BeforeEach
    void setUp() {
        mockPaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);
        mockPaymentOutboxUseCase = Mockito.mock(PaymentOutboxUseCase.class);

        paymentStatusService = new PaymentStatusServiceImpl(
                mockPaymentLoadUseCase,
                mockPaymentOutboxUseCase
        );
    }

    @Nested
    @DisplayName("getPaymentStatus() — Outbox 상태 기반")
    class OutboxStatusTest {

        @Test
        @DisplayName("Outbox 상태가 PENDING이면 PENDING 반환")
        void returnsStatusPending_WhenOutboxIsPending() {
            // given
            String orderId = "order-1";
            given(mockPaymentOutboxUseCase.findActiveOutboxStatus(orderId))
                    .willReturn(Optional.of(PaymentOutboxStatus.PENDING));

            // when
            PaymentStatusResult result = paymentStatusService.getPaymentStatus(orderId);

            // then
            assertThat(result.getOrderId()).isEqualTo(orderId);
            assertThat(result.getStatus()).isEqualTo(StatusType.PENDING);
            assertThat(result.getApprovedAt()).isNull();
        }

        @Test
        @DisplayName("Outbox 상태가 IN_FLIGHT이면 PROCESSING 반환")
        void returnsStatusProcessing_WhenOutboxIsInFlight() {
            // given
            String orderId = "order-1";
            given(mockPaymentOutboxUseCase.findActiveOutboxStatus(orderId))
                    .willReturn(Optional.of(PaymentOutboxStatus.IN_FLIGHT));

            // when
            PaymentStatusResult result = paymentStatusService.getPaymentStatus(orderId);

            // then
            assertThat(result.getStatus()).isEqualTo(StatusType.PROCESSING);
            assertThat(result.getApprovedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("getPaymentStatus() — PaymentEvent 상태 기반")
    class EventStatusTest {

        @Test
        @DisplayName("Outbox 없고 이벤트 상태가 DONE이면 DONE + approvedAt 반환")
        void returnsStatusDone_WhenEventIsDone() {
            // given
            String orderId = "order-1";
            LocalDateTime approvedAt = LocalDateTime.of(2026, 3, 18, 12, 0);
            given(mockPaymentOutboxUseCase.findActiveOutboxStatus(orderId))
                    .willReturn(Optional.empty());
            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId))
                    .willReturn(createPaymentEvent(orderId, PaymentEventStatus.DONE, approvedAt));

            // when
            PaymentStatusResult result = paymentStatusService.getPaymentStatus(orderId);

            // then
            assertThat(result.getStatus()).isEqualTo(StatusType.DONE);
            assertThat(result.getApprovedAt()).isEqualTo(approvedAt);
        }

        @Test
        @DisplayName("Outbox 없고 이벤트 상태가 FAILED이면 FAILED 반환")
        void returnsStatusFailed_WhenEventIsFailed() {
            // given
            String orderId = "order-1";
            given(mockPaymentOutboxUseCase.findActiveOutboxStatus(orderId))
                    .willReturn(Optional.empty());
            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId))
                    .willReturn(createPaymentEvent(orderId, PaymentEventStatus.FAILED, null));

            // when
            PaymentStatusResult result = paymentStatusService.getPaymentStatus(orderId);

            // then
            assertThat(result.getStatus()).isEqualTo(StatusType.FAILED);
        }

        @Test
        @DisplayName("Outbox 없고 이벤트 상태가 IN_PROGRESS이면 PROCESSING 반환")
        void returnsStatusProcessing_WhenEventIsInProgress() {
            // given
            String orderId = "order-1";
            given(mockPaymentOutboxUseCase.findActiveOutboxStatus(orderId))
                    .willReturn(Optional.empty());
            given(mockPaymentLoadUseCase.getPaymentEventByOrderId(orderId))
                    .willReturn(createPaymentEvent(orderId, PaymentEventStatus.IN_PROGRESS, null));

            // when
            PaymentStatusResult result = paymentStatusService.getPaymentStatus(orderId);

            // then
            assertThat(result.getStatus()).isEqualTo(StatusType.PROCESSING);
        }
    }

    private PaymentEvent createPaymentEvent(String orderId, PaymentEventStatus status, LocalDateTime approvedAt) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .orderId(orderId)
                .status(status)
                .approvedAt(approvedAt)
                .paymentOrderList(Collections.emptyList())
                .allArgsBuild();
    }
}
