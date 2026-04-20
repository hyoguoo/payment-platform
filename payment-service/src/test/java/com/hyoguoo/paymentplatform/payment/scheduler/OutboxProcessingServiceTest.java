package com.hyoguoo.paymentplatform.payment.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.config.RetryPolicyProperties;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.RetryPolicy;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.vo.PaymentDetails;
import com.hyoguoo.paymentplatform.payment.domain.dto.vo.PaymentFailure;
import com.hyoguoo.paymentplatform.payment.domain.enums.BackoffType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentGatewayInfo;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayStatusUnmappedException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@DisplayName("OutboxProcessingService 테스트")
class OutboxProcessingServiceTest {

    private static final String ORDER_ID = "order-123";
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 3, 15, 12, 0, 0);
    // maxAttempts=3 — 소진 임계값을 작게 설정해 FCG 경로 테스트 편의성 확보
    private static final int MAX_ATTEMPTS = 3;

    private PaymentOutboxUseCase mockPaymentOutboxUseCase;
    private PaymentLoadUseCase mockPaymentLoadUseCase;
    private PaymentCommandUseCase mockPaymentCommandUseCase;
    private PaymentTransactionCoordinator mockTransactionCoordinator;
    private RetryPolicyProperties retryPolicyProperties;
    private LocalDateTimeProvider mockLocalDateTimeProvider;
    private OutboxProcessingService outboxProcessingService;

    @BeforeEach
    void setUp() {
        mockPaymentOutboxUseCase = Mockito.mock(PaymentOutboxUseCase.class);
        mockPaymentLoadUseCase = Mockito.mock(PaymentLoadUseCase.class);
        mockPaymentCommandUseCase = Mockito.mock(PaymentCommandUseCase.class);
        mockTransactionCoordinator = Mockito.mock(PaymentTransactionCoordinator.class);
        mockLocalDateTimeProvider = Mockito.mock(LocalDateTimeProvider.class);
        retryPolicyProperties = new RetryPolicyProperties(MAX_ATTEMPTS, BackoffType.FIXED, 5000L, 60000L);
        given(mockLocalDateTimeProvider.now()).willReturn(FIXED_NOW);

        outboxProcessingService = new OutboxProcessingService(
                mockPaymentOutboxUseCase,
                mockPaymentLoadUseCase,
                mockPaymentCommandUseCase,
                mockTransactionCoordinator,
                retryPolicyProperties,
                mockLocalDateTimeProvider
        );
    }

    // ─── claimToInFlight 실패 ───────────────────────────────────────────────

    @Test
    @DisplayName("process - claimToInFlight empty 반환: downstream 호출 없음")
    void process_ClaimFails_Returns() throws Exception {
        // given
        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.empty());

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockPaymentCommandUseCase).should(never()).getPaymentStatusByOrderId(anyString(), any());
        then(mockPaymentCommandUseCase).should(never()).confirmPaymentWithGateway(any());
        then(mockTransactionCoordinator).should(never())
                .executePaymentSuccessCompletionWithOutbox(any(), any(), any());
        then(mockTransactionCoordinator).should(never())
                .executePaymentRetryWithOutbox(any(), any(), any(), any());
        then(mockTransactionCoordinator).should(never())
                .executePaymentFailureCompensationWithOutbox(anyString(), any(), anyString());
        then(mockTransactionCoordinator).should(never())
                .executePaymentQuarantineWithOutbox(any(), any(), anyString());
    }

    // ─── loadPaymentEvent 실패 ─────────────────────────────────────────────

    @Test
    @DisplayName("process - getPaymentEventByOrderId 예외 발생 시 incrementRetryOrFail 호출")
    void process_paymentEvent_로드실패_incrementRetryOrFail_호출() throws Exception {
        // given
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, 0);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        willThrow(new RuntimeException("event not found"))
                .given(mockPaymentLoadUseCase).getPaymentEventByOrderId(ORDER_ID);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockPaymentOutboxUseCase).should(times(1)).incrementRetryOrFail(ORDER_ID, inFlightOutbox);
        then(mockPaymentCommandUseCase).should(never()).getPaymentStatusByOrderId(anyString(), any());
        then(mockPaymentCommandUseCase).should(never()).confirmPaymentWithGateway(any());
    }

    // ─── 로컬 종결 재진입 차단 ─────────────────────────────────────────────

    @Test
    @DisplayName("process - 로컬 종결(DONE) 재진입: outbox 멱등 종결, confirmPaymentWithGateway/getStatus 미호출")
    void process_LocalTerminal_RejectsReentry() throws Exception {
        // given
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, 0);
        PaymentEvent doneEvent = createPaymentEventWithStatus(ORDER_ID, PaymentEventStatus.DONE);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(doneEvent);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockPaymentCommandUseCase).should(never()).getPaymentStatusByOrderId(anyString(), any());
        then(mockPaymentCommandUseCase).should(never()).confirmPaymentWithGateway(any());
        then(mockTransactionCoordinator).should(never())
                .executePaymentSuccessCompletionWithOutbox(any(), any(), any());
        then(mockTransactionCoordinator).should(never())
                .executePaymentRetryWithOutbox(any(), any(), any(), any());
        then(mockTransactionCoordinator).should(never())
                .executePaymentFailureCompensationWithOutbox(anyString(), any(), anyString());
        then(mockTransactionCoordinator).should(never())
                .executePaymentQuarantineWithOutbox(any(), any(), anyString());
        // outbox 멱등 종결 — save 또는 save 관련 호출이 있어야 함
        then(mockPaymentOutboxUseCase).should(times(1)).save(any(PaymentOutbox.class));
    }

    // ─── PG getStatus 정상 응답 경로 ───────────────────────────────────────

    @Test
    @DisplayName("process - PG DONE + approvedAt 존재: executePaymentSuccessCompletionWithOutbox 1회 호출")
    void process_PgDone_ApprovedAt_CompletesSuccess() throws Exception {
        // given: retryCount=1 (재시도 경로에서만 PG 상태 선조회 발생)
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, 1);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);
        PaymentStatusResult doneResult = createStatusResult(PaymentStatus.DONE, FIXED_NOW);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        given(mockPaymentCommandUseCase.getPaymentStatusByOrderId(eq(ORDER_ID), any())).willReturn(doneResult);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentSuccessCompletionWithOutbox(any(PaymentEvent.class), any(LocalDateTime.class),
                        any(PaymentOutbox.class));
        then(mockTransactionCoordinator).should(never())
                .executePaymentRetryWithOutbox(any(), any(), any(), any());
        then(mockTransactionCoordinator).should(never())
                .executePaymentFailureCompensationWithOutbox(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("process - PG CANCELED(terminal): executePaymentFailureCompensationWithOutbox 1회 호출")
    void process_PgTerminalFail_CompensatesFailure() throws Exception {
        // given: retryCount=1 (재시도 경로에서만 PG 상태 선조회 발생)
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, 1);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);
        PaymentStatusResult canceledResult = createStatusResult(PaymentStatus.CANCELED, null);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        given(mockPaymentCommandUseCase.getPaymentStatusByOrderId(eq(ORDER_ID), any())).willReturn(canceledResult);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentFailureCompensationWithOutbox(anyString(), any(), anyString());
        then(mockTransactionCoordinator).should(never())
                .executePaymentSuccessCompletionWithOutbox(any(), any(), any());
        then(mockTransactionCoordinator).should(never())
                .executePaymentRetryWithOutbox(any(), any(), any(), any());
    }

    // ─── ATTEMPT_CONFIRM 경로 ─────────────────────────────────────────────

    @Test
    @DisplayName("process - 최초 시도(retryCount=0): PG 상태 조회 없이 바로 confirmPaymentWithGateway 성공 → executePaymentSuccessCompletionWithOutbox 1회")
    void process_FirstAttempt_AttemptsConfirm_ThenSuccess() throws Exception {
        // given: retryCount=0 → PG 선조회 없이 바로 confirm
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, 0);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);
        PaymentGatewayInfo successInfo = createGatewayInfo(FIXED_NOW);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willReturn(successInfo);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then: PG 상태 조회 없이 바로 confirm 호출
        then(mockPaymentCommandUseCase).should(never()).getPaymentStatusByOrderId(anyString(), any());
        then(mockPaymentCommandUseCase).should(times(1))
                .confirmPaymentWithGateway(any(PaymentConfirmCommand.class));
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentSuccessCompletionWithOutbox(any(PaymentEvent.class), any(LocalDateTime.class),
                        any(PaymentOutbox.class));
        then(mockTransactionCoordinator).should(never())
                .executePaymentRetryWithOutbox(any(), any(), any(), any());
    }

    @Test
    @DisplayName("process - 최초 시도(retryCount=0) + confirmPaymentWithGateway RETRYABLE_FAILURE + 미소진: executePaymentRetryWithOutbox")
    void process_FirstAttempt_AttemptsConfirm_ThenRetryable() throws Exception {
        // given: retryCount=0 → PG 선조회 없이 바로 confirm
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, 0);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);
        PaymentGatewayInfo retryableInfo = createFailureGatewayInfo(PaymentConfirmResultStatus.RETRYABLE_FAILURE);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willReturn(retryableInfo);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentRetryWithOutbox(any(PaymentEvent.class), any(PaymentOutbox.class),
                        any(RetryPolicy.class), any(LocalDateTime.class));
        then(mockTransactionCoordinator).should(never())
                .executePaymentSuccessCompletionWithOutbox(any(), any(), any());
        then(mockTransactionCoordinator).should(never())
                .executePaymentFailureCompensationWithOutbox(anyString(), any(), anyString());
    }

    // ─── Retryable 예외 경로 ──────────────────────────────────────────────

    @Test
    @DisplayName("process - getStatus RetryableException + 미소진: executePaymentRetryWithOutbox")
    void process_RetryableException_UnderLimit_RetriesLater() throws Exception {
        // given: retryCount=1, maxAttempts=3 → 미소진 (재시도 경로에서만 PG 상태 선조회 발생)
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, 1);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);
        PaymentGatewayRetryableException retryable =
                PaymentGatewayRetryableException.of(PaymentErrorCode.GATEWAY_RETRYABLE_ERROR);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        given(mockPaymentCommandUseCase.getPaymentStatusByOrderId(eq(ORDER_ID), any())).willThrow(retryable);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentRetryWithOutbox(any(PaymentEvent.class), any(PaymentOutbox.class),
                        any(RetryPolicy.class), any(LocalDateTime.class));
        then(mockTransactionCoordinator).should(never())
                .executePaymentQuarantineWithOutbox(any(), any(), anyString());
    }

    @Test
    @DisplayName("process - RetryableException 소진(retryCount=N-1) + FCG getStatus retryable → executePaymentQuarantineWithOutbox, getStatus 2회 호출, retryCount 미증가")
    void process_RetryableException_Exhausted_FcgCallsGetStatus_ThenQuarantines() throws Exception {
        // given: retryCount = MAX_ATTEMPTS-1 = 2 → 이번 retry 후 소진 판정
        int exhaustingRetryCount = MAX_ATTEMPTS - 1;
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, exhaustingRetryCount);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);
        PaymentGatewayRetryableException retryable =
                PaymentGatewayRetryableException.of(PaymentErrorCode.GATEWAY_RETRYABLE_ERROR);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        // main + FCG 모두 retryable 예외
        given(mockPaymentCommandUseCase.getPaymentStatusByOrderId(eq(ORDER_ID), any())).willThrow(retryable);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then: getStatusByOrderId 2회 호출 (main 1회 + FCG 1회)
        then(mockPaymentCommandUseCase).should(times(2)).getPaymentStatusByOrderId(eq(ORDER_ID), any());
        // quarantine
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentQuarantineWithOutbox(any(PaymentEvent.class), any(PaymentOutbox.class), anyString());
        then(mockTransactionCoordinator).should(never())
                .executePaymentRetryWithOutbox(any(), any(), any(), any());
    }

    @Test
    @DisplayName("process - RetryableException 소진 + FCG getStatus DONE → executePaymentSuccessCompletionWithOutbox")
    void process_RetryableException_Exhausted_FcgCallsGetStatus_ThenDone() throws Exception {
        // given: retryCount = MAX_ATTEMPTS-1 → 소진
        int exhaustingRetryCount = MAX_ATTEMPTS - 1;
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, exhaustingRetryCount);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);
        PaymentGatewayRetryableException retryable =
                PaymentGatewayRetryableException.of(PaymentErrorCode.GATEWAY_RETRYABLE_ERROR);
        PaymentStatusResult doneResult = createStatusResult(PaymentStatus.DONE, FIXED_NOW);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        // main: retryable, FCG: DONE
        given(mockPaymentCommandUseCase.getPaymentStatusByOrderId(eq(ORDER_ID), any()))
                .willThrow(retryable)
                .willReturn(doneResult);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then: getStatusByOrderId 2회 (main + FCG)
        then(mockPaymentCommandUseCase).should(times(2)).getPaymentStatusByOrderId(eq(ORDER_ID), any());
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentSuccessCompletionWithOutbox(any(PaymentEvent.class), any(LocalDateTime.class),
                        any(PaymentOutbox.class));
        then(mockTransactionCoordinator).should(never())
                .executePaymentQuarantineWithOutbox(any(), any(), anyString());
    }

    // ─── UnmappedStatus 예외 경로 ─────────────────────────────────────────

    @Test
    @DisplayName("process - getStatus UnmappedException + 미소진: executePaymentRetryWithOutbox")
    void process_UnmappedStatus_UnderLimit_RetriesLater() throws Exception {
        // given: retryCount=1 → 미소진 (재시도 경로에서만 PG 상태 선조회 발생)
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, 1);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);
        PaymentGatewayStatusUnmappedException unmapped =
                PaymentGatewayStatusUnmappedException.of("SOME_UNKNOWN");

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        given(mockPaymentCommandUseCase.getPaymentStatusByOrderId(eq(ORDER_ID), any())).willThrow(unmapped);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentRetryWithOutbox(any(PaymentEvent.class), any(PaymentOutbox.class),
                        any(RetryPolicy.class), any(LocalDateTime.class));
        then(mockTransactionCoordinator).should(never())
                .executePaymentQuarantineWithOutbox(any(), any(), anyString());
    }

    @Test
    @DisplayName("process - getStatus UnmappedException + 소진(retryCount=N-1) → FCG도 unmapped → executePaymentQuarantineWithOutbox")
    void process_UnmappedStatus_Exhausted_Quarantines() throws Exception {
        // given: retryCount = MAX_ATTEMPTS-1 → 소진
        int exhaustingRetryCount = MAX_ATTEMPTS - 1;
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, exhaustingRetryCount);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);
        PaymentGatewayStatusUnmappedException unmapped =
                PaymentGatewayStatusUnmappedException.of("SOME_UNKNOWN");

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        given(mockPaymentCommandUseCase.getPaymentStatusByOrderId(eq(ORDER_ID), any())).willThrow(unmapped);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentQuarantineWithOutbox(any(PaymentEvent.class), any(PaymentOutbox.class), anyString());
        then(mockTransactionCoordinator).should(never())
                .executePaymentRetryWithOutbox(any(), any(), any(), any());
    }

    // ─── 기존 회귀 케이스 (NON_RETRYABLE 즉시 보상, RETRYABLE 소진→보상) ──

    @Test
    @DisplayName("process - 최초 시도 + confirmPaymentWithGateway NON_RETRYABLE_FAILURE: executePaymentFailureCompensationWithOutbox 호출")
    void process_nonRetryable결과_executePaymentFailureCompensationWithOutbox_호출() throws Exception {
        // given: retryCount=0 → 바로 confirm → NON_RETRYABLE_FAILURE
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, 0);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);
        PaymentGatewayInfo nonRetryableGatewayInfo =
                createFailureGatewayInfo(PaymentConfirmResultStatus.NON_RETRYABLE_FAILURE);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willReturn(nonRetryableGatewayInfo);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentFailureCompensationWithOutbox(anyString(), any(), anyString());
        then(mockPaymentOutboxUseCase).should(never()).incrementRetryOrFail(any(), any());
    }

    @Test
    @DisplayName("process - 재시도 경로에서 ATTEMPT_CONFIRM → RETRYABLE_FAILURE + 소진 → FCG getStatus retryable → quarantine")
    void process_retryable결과_소진_executePaymentQuarantineWithOutbox_호출() throws Exception {
        // given: retryCount=N-1 → 재시도 경로, getStatus → notFound → ATTEMPT_CONFIRM
        //        confirm → RETRYABLE_FAILURE, FCG getStatus → retryable → quarantine
        int exhaustingRetryCount = MAX_ATTEMPTS - 1;
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, exhaustingRetryCount);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);
        PaymentGatewayNonRetryableException notFound =
                PaymentGatewayNonRetryableException.of(PaymentErrorCode.GATEWAY_NON_RETRYABLE_ERROR);
        PaymentGatewayInfo retryableGatewayInfo =
                createFailureGatewayInfo(PaymentConfirmResultStatus.RETRYABLE_FAILURE);
        PaymentGatewayRetryableException fcgRetryable =
                PaymentGatewayRetryableException.of(PaymentErrorCode.GATEWAY_RETRYABLE_ERROR);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        // main call → ATTEMPT_CONFIRM(notFound), FCG call → retryable → quarantine
        given(mockPaymentCommandUseCase.getPaymentStatusByOrderId(eq(ORDER_ID), any()))
                .willThrow(notFound)
                .willThrow(fcgRetryable);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willReturn(retryableGatewayInfo);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentQuarantineWithOutbox(any(PaymentEvent.class), any(PaymentOutbox.class), anyString());
        then(mockTransactionCoordinator).should(never())
                .executePaymentRetryWithOutbox(any(), any(), any(), any());
    }

    // ─── GUARD_MISSING_APPROVED_AT 경로 ──────────────────────────────────

    @Test
    @DisplayName("process - PG DONE + approvedAt null + 미소진: executePaymentRetryWithOutbox 1회 호출")
    void process_GuardMissingApprovedAt_UnderLimit_Retries() throws Exception {
        // given: retryCount=1, maxAttempts=3 → 미소진 (재시도 경로에서만 PG 상태 선조회 발생)
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, 1);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);
        // PG DONE 이지만 approvedAt null
        PaymentStatusResult doneNullAt = createStatusResult(PaymentStatus.DONE, null);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        given(mockPaymentCommandUseCase.getPaymentStatusByOrderId(eq(ORDER_ID), any())).willReturn(doneNullAt);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then: retry 호출, quarantine 미호출
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentRetryWithOutbox(any(PaymentEvent.class), any(PaymentOutbox.class),
                        any(RetryPolicy.class), any(LocalDateTime.class));
        then(mockTransactionCoordinator).should(never())
                .executePaymentQuarantineWithOutbox(any(), any(), anyString());
        then(mockTransactionCoordinator).should(never())
                .executePaymentSuccessCompletionWithOutbox(any(), any(), any());
    }

    @Test
    @DisplayName("process - PG DONE + approvedAt null + 소진(retryCount=N-1) → FCG → quarantine")
    void process_GuardMissingApprovedAt_Exhausted_FcgQuarantines() throws Exception {
        // given: retryCount = MAX_ATTEMPTS-1 = 2 → 이번 retry 후 소진 판정
        int exhaustingRetryCount = MAX_ATTEMPTS - 1;
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, exhaustingRetryCount);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);
        // 두 번 모두 PG DONE + approvedAt null (FCG도 동일)
        PaymentStatusResult doneNullAt = createStatusResult(PaymentStatus.DONE, null);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        given(mockPaymentCommandUseCase.getPaymentStatusByOrderId(eq(ORDER_ID), any())).willReturn(doneNullAt);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then: getStatus 2회 (main + FCG), quarantine 1회
        then(mockPaymentCommandUseCase).should(times(2)).getPaymentStatusByOrderId(eq(ORDER_ID), any());
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentQuarantineWithOutbox(any(PaymentEvent.class), any(PaymentOutbox.class), anyString());
        then(mockTransactionCoordinator).should(never())
                .executePaymentRetryWithOutbox(any(), any(), any(), any());
        then(mockTransactionCoordinator).should(never())
                .executePaymentSuccessCompletionWithOutbox(any(), any(), any());
    }

    // ─── ATTEMPT_CONFIRM gatewayType 전파 ────────────────────────────────

    @Test
    @DisplayName("handleAttemptConfirm - NICEPAY gatewayType이 PaymentConfirmCommand에 전파된다")
    void handleAttemptConfirm_PropagatesGatewayType_ToConfirmCommand() throws Exception {
        // given: retryCount=0 → 바로 confirm, paymentEvent의 gatewayType=NICEPAY
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, 0);
        PaymentEvent nicepayEvent = createPaymentEventWithGatewayType(ORDER_ID, PaymentGatewayType.NICEPAY);
        PaymentGatewayInfo successInfo = createGatewayInfo(FIXED_NOW);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(nicepayEvent);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willReturn(successInfo);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then: confirmPaymentWithGateway에 전달된 command의 gatewayType이 NICEPAY여야 한다
        ArgumentCaptor<PaymentConfirmCommand> commandCaptor =
                ArgumentCaptor.forClass(PaymentConfirmCommand.class);
        then(mockPaymentCommandUseCase).should(times(1))
                .confirmPaymentWithGateway(commandCaptor.capture());
        assertThat(commandCaptor.getValue().getGatewayType()).isEqualTo(PaymentGatewayType.NICEPAY);
    }

    // ─── handleAttemptConfirm 예외 경로 ─────────────────────────────────────

    @Test
    @DisplayName("process - 최초 시도 + confirmPaymentWithGateway RetryableException + 미소진: executePaymentRetryWithOutbox")
    void process_FirstAttempt_ConfirmRetryableException_UnderLimit_Retries() throws Exception {
        // given: retryCount=0 → 바로 confirm → RetryableException
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, 0);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willThrow(PaymentGatewayRetryableException.of(PaymentErrorCode.GATEWAY_RETRYABLE_ERROR));

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentRetryWithOutbox(any(PaymentEvent.class), any(PaymentOutbox.class),
                        any(RetryPolicy.class), any(LocalDateTime.class));
        then(mockTransactionCoordinator).should(never())
                .executePaymentFailureCompensationWithOutbox(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("process - 최초 시도 + confirmPaymentWithGateway RetryableException + 소진: FCG → quarantine")
    void process_FirstAttempt_ConfirmRetryableException_Exhausted_FcgQuarantines() throws Exception {
        // given: retryCount=MAX_ATTEMPTS-1 → 재시도 경로에서 ATTEMPT_CONFIRM → confirm → RetryableException → 소진 → FCG
        int exhaustingRetryCount = MAX_ATTEMPTS - 1;
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, exhaustingRetryCount);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);
        PaymentGatewayNonRetryableException notFound =
                PaymentGatewayNonRetryableException.of(PaymentErrorCode.GATEWAY_NON_RETRYABLE_ERROR);
        PaymentGatewayRetryableException retryable =
                PaymentGatewayRetryableException.of(PaymentErrorCode.GATEWAY_RETRYABLE_ERROR);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        // main getStatus → notFound → ATTEMPT_CONFIRM, FCG getStatus → retryable → quarantine
        given(mockPaymentCommandUseCase.getPaymentStatusByOrderId(eq(ORDER_ID), any()))
                .willThrow(notFound)
                .willThrow(retryable);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willThrow(PaymentGatewayRetryableException.of(PaymentErrorCode.GATEWAY_RETRYABLE_ERROR));

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentQuarantineWithOutbox(any(PaymentEvent.class), any(PaymentOutbox.class), anyString());
    }

    @Test
    @DisplayName("process - 최초 시도 + confirmPaymentWithGateway NonRetryableException: executePaymentFailureCompensationWithOutbox")
    void process_FirstAttempt_ConfirmNonRetryableException_CompensatesFailure() throws Exception {
        // given: retryCount=0 → 바로 confirm → NonRetryableException
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, 0);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willThrow(PaymentGatewayNonRetryableException.of(PaymentErrorCode.GATEWAY_NON_RETRYABLE_ERROR));

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentFailureCompensationWithOutbox(anyString(), any(), anyString());
        then(mockTransactionCoordinator).should(never())
                .executePaymentRetryWithOutbox(any(), any(), any(), any());
    }

    // ─── FCG COMPLETE_FAILURE 경로 ──────────────────────────────────────────

    @Test
    @DisplayName("process - 소진 + FCG getStatus CANCELED: executePaymentFailureCompensationWithOutbox")
    void process_Exhausted_FcgCanceled_CompensatesFailure() throws Exception {
        // given: retryCount=MAX_ATTEMPTS-1, main getStatus → retryable → 소진 → FCG → CANCELED
        int exhaustingRetryCount = MAX_ATTEMPTS - 1;
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, exhaustingRetryCount);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);
        PaymentGatewayRetryableException retryable =
                PaymentGatewayRetryableException.of(PaymentErrorCode.GATEWAY_RETRYABLE_ERROR);
        PaymentStatusResult canceledResult = createStatusResult(PaymentStatus.CANCELED, null);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        // main: retryable → 소진 → FCG: CANCELED
        given(mockPaymentCommandUseCase.getPaymentStatusByOrderId(eq(ORDER_ID), any()))
                .willThrow(retryable)
                .willReturn(canceledResult);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentFailureCompensationWithOutbox(anyString(), any(), anyString());
        then(mockTransactionCoordinator).should(never())
                .executePaymentQuarantineWithOutbox(any(), any(), anyString());
    }

    // ─── NonRetryable 예외 경로 ──────────────────────────────────────────────

    @Test
    @DisplayName("process - getStatus NonRetryableException → ATTEMPT_CONFIRM → confirm 성공: executePaymentSuccessCompletionWithOutbox")
    void process_NonRetryableException_AttemptConfirm_ThenSuccess() throws Exception {
        // given: retryCount=1 (재시도 경로), getStatus NotFound → ATTEMPT_CONFIRM → confirm 성공
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, 1);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);
        PaymentGatewayInfo successInfo = createGatewayInfo(FIXED_NOW);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        given(mockPaymentCommandUseCase.getPaymentStatusByOrderId(eq(ORDER_ID), any()))
                .willThrow(PaymentGatewayNonRetryableException.of(PaymentErrorCode.GATEWAY_NON_RETRYABLE_ERROR));
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willReturn(successInfo);

        // when
        outboxProcessingService.process(ORDER_ID);

        // then: NonRetryableException → ATTEMPT_CONFIRM, confirm 성공 → success
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentSuccessCompletionWithOutbox(any(PaymentEvent.class), any(LocalDateTime.class),
                        any(PaymentOutbox.class));
    }

    @Test
    @DisplayName("process - getStatus NonRetryableException → ATTEMPT_CONFIRM → confirm NonRetryableException: executePaymentFailureCompensationWithOutbox")
    void process_NonRetryableException_AttemptConfirm_ThenNonRetryable_CompensatesFailure() throws Exception {
        // given: retryCount=1 (재시도 경로), getStatus NotFound → ATTEMPT_CONFIRM → confirm NonRetryable
        PaymentOutbox inFlightOutbox = createInFlightOutbox(ORDER_ID, 1);
        PaymentEvent inProgressEvent = createPaymentEvent(ORDER_ID);

        given(mockPaymentOutboxUseCase.claimToInFlight(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentLoadUseCase.getPaymentEventByOrderId(ORDER_ID)).willReturn(inProgressEvent);
        given(mockPaymentCommandUseCase.getPaymentStatusByOrderId(eq(ORDER_ID), any()))
                .willThrow(PaymentGatewayNonRetryableException.of(PaymentErrorCode.GATEWAY_NON_RETRYABLE_ERROR));
        given(mockPaymentCommandUseCase.confirmPaymentWithGateway(any(PaymentConfirmCommand.class)))
                .willThrow(PaymentGatewayNonRetryableException.of(PaymentErrorCode.GATEWAY_NON_RETRYABLE_ERROR));

        // when
        outboxProcessingService.process(ORDER_ID);

        // then
        then(mockTransactionCoordinator).should(times(1))
                .executePaymentFailureCompensationWithOutbox(anyString(), any(), anyString());
        then(mockTransactionCoordinator).should(never())
                .executePaymentRetryWithOutbox(any(), any(), any(), any());
    }

    // ─── 헬퍼 메서드 ──────────────────────────────────────────────────────

    private PaymentOutbox createInFlightOutbox(String orderId, int retryCount) {
        return PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(orderId)
                .status(PaymentOutboxStatus.IN_FLIGHT)
                .retryCount(retryCount)
                .allArgsBuild();
    }

    private PaymentEvent createPaymentEvent(String orderId) {
        return createPaymentEventWithStatus(orderId, PaymentEventStatus.IN_PROGRESS);
    }

    private PaymentEvent createPaymentEventWithStatus(String orderId, PaymentEventStatus status) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(1L)
                .orderId(orderId)
                .paymentKey("payment-key-123")
                .status(status)
                .paymentOrderList(Collections.emptyList())
                .allArgsBuild();
    }

    private PaymentEvent createPaymentEventWithGatewayType(String orderId, PaymentGatewayType gatewayType) {
        return PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(1L)
                .orderId(orderId)
                .paymentKey("payment-key-123")
                .status(PaymentEventStatus.IN_PROGRESS)
                .gatewayType(gatewayType)
                .paymentOrderList(Collections.emptyList())
                .allArgsBuild();
    }

    private PaymentStatusResult createStatusResult(PaymentStatus status, LocalDateTime approvedAt) {
        return new PaymentStatusResult(
                "payment-key-123",
                ORDER_ID,
                status,
                BigDecimal.valueOf(10000),
                approvedAt,
                null
        );
    }

    private PaymentGatewayInfo createGatewayInfo(LocalDateTime approvedAt) {
        return PaymentGatewayInfo.builder()
                .paymentKey("payment-key-123")
                .orderId(ORDER_ID)
                .paymentConfirmResultStatus(PaymentConfirmResultStatus.SUCCESS)
                .paymentDetails(
                        PaymentDetails.builder()
                                .approvedAt(approvedAt)
                                .totalAmount(BigDecimal.valueOf(10000))
                                .build()
                )
                .build();
    }

    private PaymentGatewayInfo createFailureGatewayInfo(PaymentConfirmResultStatus status) {
        return PaymentGatewayInfo.builder()
                .paymentKey("payment-key-123")
                .orderId(ORDER_ID)
                .paymentConfirmResultStatus(status)
                .paymentFailure(
                        PaymentFailure.builder()
                                .code("FAILURE_CODE")
                                .message("failure reason")
                                .build()
                )
                .build();
    }
}
