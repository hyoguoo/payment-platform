package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.core.common.metrics.PaymentQuarantineMetrics;
import com.hyoguoo.paymentplatform.mock.TestLocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayPort;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentFailureInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentGatewayInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PaymentCommandUseCaseTest {

    private PaymentCommandUseCase paymentCommandUseCase;
    private PaymentEventRepository mockPaymentEventRepository;
    private PaymentGatewayPort mockPaymentGatewayPort;
    private TestLocalDateTimeProvider testLocalDateTimeProvider;
    private PaymentQuarantineMetrics mockPaymentQuarantineMetrics;

    @BeforeEach
    void setUp() {
        mockPaymentEventRepository = Mockito.mock(PaymentEventRepository.class);
        mockPaymentGatewayPort = Mockito.mock(PaymentGatewayPort.class);
        testLocalDateTimeProvider = new TestLocalDateTimeProvider();
        mockPaymentQuarantineMetrics = Mockito.mock(PaymentQuarantineMetrics.class);
        paymentCommandUseCase = new PaymentCommandUseCase(
                mockPaymentEventRepository,
                mockPaymentGatewayPort,
                testLocalDateTimeProvider,
                mockPaymentQuarantineMetrics
        );
    }

    @Test
    @DisplayName("결제 시작을 호출하고 성공적으로 처리된 PaymentEvent를 반환한다.")
    void testExecutePayment_Success() {
        // given
        String paymentKey = "paymentKey";
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                .orderId("order123")
                .paymentKey(paymentKey)
                .amount(new BigDecimal(10000))
                .build();

        // when
        when(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .thenReturn(paymentEvent);
        PaymentEvent result = paymentCommandUseCase.executePayment(paymentEvent, paymentConfirmCommand.getPaymentKey());

        // then
        verify(paymentEvent, times(1)).execute(paymentKey, testLocalDateTimeProvider.now(),
                testLocalDateTimeProvider.now());
        assertThat(result).isEqualTo(paymentEvent);
    }

    @Test
    @DisplayName("결제 완료 처리를 호출하고 성공적으로 완료된 PaymentEvent를 반환한다.")
    void testMarkPaymentAsDone() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        LocalDateTime approvedAt = LocalDateTime.of(2021, 1, 1, 0, 0, 0);

        // when
        when(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .thenReturn(paymentEvent);
        PaymentEvent result = paymentCommandUseCase.markPaymentAsDone(paymentEvent, approvedAt);

        // then
        verify(paymentEvent, times(1)).done(approvedAt, testLocalDateTimeProvider.now());
        assertThat(result.getId()).isEqualTo(paymentEvent.getId());

    }

    @Test
    @DisplayName("결제 실패 처리를 호출하고 성공적으로 실패된 PaymentEvent를 반환한다.")
    void testMarkPaymentAsFail() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        String failureReason = "";

        // when
        when(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .thenReturn(paymentEvent);
        PaymentEvent result = paymentCommandUseCase.markPaymentAsFail(paymentEvent, failureReason);

        // then
        verify(paymentEvent, times(1)).fail(failureReason, testLocalDateTimeProvider.now());
        assertThat(result).isEqualTo(paymentEvent);
    }

    @Test
    @DisplayName("Toss 결제 승인 성공 시 성공 결과와 함께 결제 정보를 반환한다.")
    void testConfirmPaymentWithGateway_Success() throws Exception {
        // given
        PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                .orderId("order123")
                .paymentKey("paymentKey")
                .amount(new BigDecimal(10000))
                .gatewayType(PaymentGatewayType.TOSS)
                .build();

        PaymentConfirmResult confirmResult =
                new PaymentConfirmResult(
                        PaymentConfirmResultStatus.SUCCESS,
                        "paymentKey",
                        "order123",
                        new BigDecimal(10000),
                        LocalDateTime.now(),
                        null
                );

        // when
        when(mockPaymentGatewayPort.confirm(
                any(PaymentConfirmRequest.class)))
                .thenReturn(confirmResult);
        PaymentGatewayInfo result = paymentCommandUseCase.confirmPaymentWithGateway(
                paymentConfirmCommand
        );

        // then
        assertThat(result.getPaymentConfirmResultStatus())
                .isEqualTo(PaymentConfirmResultStatus.SUCCESS);
    }

    @Test
    @DisplayName("Toss 결제 승인 중 재시도 가능한 실패 시 RETRYABLE_FAILURE 상태의 결과를 반환한다.")
    void testConfirmPaymentWithGateway_RetryableFailure() {
        // given
        PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                .orderId("order123")
                .paymentKey("paymentKey")
                .amount(new BigDecimal(10000))
                .gatewayType(PaymentGatewayType.TOSS)
                .build();

        PaymentConfirmResult confirmResult =
                new PaymentConfirmResult(
                        PaymentConfirmResultStatus.RETRYABLE_FAILURE,
                        "paymentKey",
                        "order123",
                        new BigDecimal(10000),
                        LocalDateTime.now(),
                        new PaymentFailureInfo("ERROR",
                                "Retryable error", true)
                );

        // when
        when(mockPaymentGatewayPort.confirm(any(PaymentConfirmRequest.class)))
                .thenReturn(confirmResult);
        PaymentGatewayInfo result = paymentCommandUseCase.confirmPaymentWithGateway(paymentConfirmCommand);

        // then
        assertThat(result.getPaymentConfirmResultStatus())
                .isEqualTo(PaymentConfirmResultStatus.RETRYABLE_FAILURE);
    }

    @Test
    @DisplayName("Toss 결제 승인 중 재시도 불가능한 실패 시 NON_RETRYABLE_FAILURE 상태의 결과를 반환한다.")
    void testConfirmPaymentWithGateway_NonRetryableFailure() {
        // given
        PaymentConfirmCommand paymentConfirmCommand = PaymentConfirmCommand.builder()
                .orderId("order123")
                .paymentKey("paymentKey")
                .amount(new BigDecimal(10000))
                .gatewayType(PaymentGatewayType.TOSS)
                .build();

        PaymentConfirmResult confirmResult =
                new PaymentConfirmResult(
                        PaymentConfirmResultStatus.NON_RETRYABLE_FAILURE,
                        "paymentKey",
                        "order123",
                        new BigDecimal(10000),
                        LocalDateTime.now(),
                        new PaymentFailureInfo("ERROR",
                                "Non-retryable error", false)
                );

        // when
        when(mockPaymentGatewayPort.confirm(any(PaymentConfirmRequest.class)))
                .thenReturn(confirmResult);
        PaymentGatewayInfo result = paymentCommandUseCase.confirmPaymentWithGateway(paymentConfirmCommand);

        // then
        assertThat(result.getPaymentConfirmResultStatus())
                .isEqualTo(PaymentConfirmResultStatus.NON_RETRYABLE_FAILURE);
    }

    @Test
    @DisplayName("markPaymentAsRetrying 호출 시 PaymentEvent.toRetrying()을 호출하고 저장한다.")
    void markPaymentAsRetrying_PaymentEvent_toRetrying_호출_및_저장() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        given(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .willReturn(paymentEvent);

        // when
        paymentCommandUseCase.markPaymentAsRetrying(paymentEvent);

        // then
        then(paymentEvent).should(times(1)).toRetrying(testLocalDateTimeProvider.now());
        then(mockPaymentEventRepository).should(times(1)).saveOrUpdate(paymentEvent);
    }

    @Test
    @DisplayName("markPaymentAsQuarantined 호출 시 quarantine()을 호출하고 payment_quarantined_total 카운터를 기록한다.")
    void markPaymentAsQuarantined_RecordsQuarantineMetric() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        String reason = "GATEWAY_STATUS_UNKNOWN";
        given(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .willReturn(paymentEvent);

        // when
        paymentCommandUseCase.markPaymentAsQuarantined(paymentEvent, reason);

        // then
        then(paymentEvent).should(times(1)).quarantine(reason, testLocalDateTimeProvider.now());
        then(mockPaymentQuarantineMetrics).should(times(1)).recordQuarantine(reason);
    }

    @Test
    @DisplayName("결제 만료 처리를 호출하고 성공적으로 만료된 PaymentEvent를 반환한다.")
    void testExpirePayment() {
        // given
        PaymentEvent paymentEvent = Mockito.mock(PaymentEvent.class);
        given(mockPaymentEventRepository.saveOrUpdate(any(PaymentEvent.class)))
                .willReturn(paymentEvent);

        // when
        PaymentEvent result = paymentCommandUseCase.expirePayment(paymentEvent);

        // then
        then(paymentEvent).should(times(1)).expire(testLocalDateTimeProvider.now());
        assertThat(result).isEqualTo(paymentEvent);
    }
}
