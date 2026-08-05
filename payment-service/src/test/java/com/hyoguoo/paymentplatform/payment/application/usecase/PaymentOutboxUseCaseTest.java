package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.config.RetryPolicyProperties;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxCreationResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.BackoffType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentOutboxDuplicateException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("PaymentOutboxUseCase 테스트")
class PaymentOutboxUseCaseTest {

    private PaymentOutboxRepository mockPaymentOutboxRepository;
    private RetryPolicyProperties retryPolicyProperties;
    private PaymentOutboxUseCase paymentOutboxUseCase;

    private static final Instant FIXED_INSTANT = Instant.parse("2026-03-15T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final String ORDER_ID = "order-123";

    @BeforeEach
    void setUp() {
        mockPaymentOutboxRepository = Mockito.mock(PaymentOutboxRepository.class);
        retryPolicyProperties = new RetryPolicyProperties(BackoffType.FIXED, 5000L, 60000L);
        paymentOutboxUseCase = new PaymentOutboxUseCase(
                mockPaymentOutboxRepository,
                FIXED_CLOCK,
                retryPolicyProperties
        );
    }

    @Test
    @DisplayName("createPendingRecord: 생성됨이면 createPendingIfAbsent를 1회 호출하고 PENDING 상태 PaymentOutbox를 반환한다")
    void createPendingRecord_created_returnsPendingOutbox() {
        // given
        given(mockPaymentOutboxRepository.createPendingIfAbsent(ORDER_ID))
                .willReturn(PaymentOutboxCreationResult.CREATED);

        // when
        PaymentOutbox result = paymentOutboxUseCase.createPendingRecord(ORDER_ID);

        // then
        assertThat(result.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
        assertThat(result.getOrderId()).isEqualTo(ORDER_ID);
        then(mockPaymentOutboxRepository).should(times(1)).createPendingIfAbsent(ORDER_ID);
        then(mockPaymentOutboxRepository).should(never()).save(any(PaymentOutbox.class));
    }

    @Test
    @DisplayName("createPendingRecord: 이미_있음(ALREADY_EXISTS)이면 PaymentOutboxDuplicateException을 던진다")
    void createPendingRecord_alreadyExists_throwsDuplicateException() {
        // given
        given(mockPaymentOutboxRepository.createPendingIfAbsent(ORDER_ID))
                .willReturn(PaymentOutboxCreationResult.ALREADY_EXISTS);

        // when & then
        assertThatThrownBy(() -> paymentOutboxUseCase.createPendingRecord(ORDER_ID))
                .isInstanceOf(PaymentOutboxDuplicateException.class);
    }

    @Test
    @DisplayName("createPendingRecord: 저장_실패(SAVE_FAILED)면 중복 재진입 예외가 아닌 예외를 던진다")
    void createPendingRecord_saveFailed_throwsNonDuplicateException() {
        // given
        given(mockPaymentOutboxRepository.createPendingIfAbsent(ORDER_ID))
                .willReturn(PaymentOutboxCreationResult.SAVE_FAILED);

        // when & then
        assertThatThrownBy(() -> paymentOutboxUseCase.createPendingRecord(ORDER_ID))
                .isNotInstanceOf(PaymentOutboxDuplicateException.class);
    }

    @Test
    @DisplayName("recoverTimedOutInFlightRecords: findTimedOutInFlight() 결과 각각 RetryPolicy 기반 incrementRetryCount(policy, now) + save() 호출")
    void recoverTimedOutInFlightRecords_RetryPolicy_기반_incrementRetryCount_전달() {
        // given
        PaymentOutbox outbox1 = PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId("order-001")
                .status(PaymentOutboxStatus.IN_FLIGHT)
                .retryCount(1)
                .inFlightAt(FIXED_INSTANT.minus(Duration.ofMinutes(10)))
                .allArgsBuild();
        PaymentOutbox outbox2 = PaymentOutbox.allArgsBuilder()
                .id(2L)
                .orderId("order-002")
                .status(PaymentOutboxStatus.IN_FLIGHT)
                .retryCount(2)
                .inFlightAt(FIXED_INSTANT.minus(Duration.ofMinutes(10)))
                .allArgsBuild();

        given(mockPaymentOutboxRepository.findTimedOutInFlight(any(Instant.class)))
                .willReturn(List.of(outbox1, outbox2));
        given(mockPaymentOutboxRepository.save(any(PaymentOutbox.class))).willReturn(outbox1);

        // when
        paymentOutboxUseCase.recoverTimedOutInFlightRecords(5);

        // then
        assertThat(outbox1.getRetryCount()).isEqualTo(2);
        assertThat(outbox1.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
        assertThat(outbox1.getNextRetryAt()).isEqualTo(FIXED_INSTANT.plus(Duration.ofSeconds(5))); // FIXED 5000ms
        assertThat(outbox2.getRetryCount()).isEqualTo(3);
        assertThat(outbox2.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
        assertThat(outbox2.getNextRetryAt()).isEqualTo(FIXED_INSTANT.plus(Duration.ofSeconds(5))); // FIXED 5000ms
        then(mockPaymentOutboxRepository).should(times(2)).save(any(PaymentOutbox.class));
    }

    @Test
    @DisplayName("findActiveOutboxStatus - PENDING: PENDING 레코드 존재 시 Optional.of(PENDING) 반환")
    void findActiveOutboxStatus_pendingRecord_returnsPendingStatus() {
        // given
        PaymentOutbox pendingOutbox = PaymentOutbox.createPending(ORDER_ID);
        given(mockPaymentOutboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(pendingOutbox));

        // when
        Optional<PaymentOutboxStatus> result = paymentOutboxUseCase.findActiveOutboxStatus(ORDER_ID);

        // then
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(PaymentOutboxStatus.PENDING);
    }

    @Test
    @DisplayName("findActiveOutboxStatus - DONE: DONE 레코드 존재 시 Optional.empty() 반환")
    void findActiveOutboxStatus_doneRecord_returnsEmpty() {
        // given
        PaymentOutbox doneOutbox = PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(ORDER_ID)
                .status(PaymentOutboxStatus.DONE)
                .retryCount(0)
                .allArgsBuild();
        given(mockPaymentOutboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(doneOutbox));

        // when
        Optional<PaymentOutboxStatus> result = paymentOutboxUseCase.findActiveOutboxStatus(ORDER_ID);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("recordPublishFailureDelay: 대기 행이면 읽은 시점의 횟수를 조건으로 조건부 갱신을 호출한다")
    void recordPublishFailureDelay_pendingOutbox_callsConditionalUpdateWithExpectedRetryCount() {
        // given
        PaymentOutbox pendingOutbox = PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(ORDER_ID)
                .status(PaymentOutboxStatus.PENDING)
                .retryCount(0)
                .allArgsBuild();
        given(mockPaymentOutboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(pendingOutbox));
        given(mockPaymentOutboxRepository.recordRetryDelay(eq(ORDER_ID), eq(0), eq(1), any(Instant.class)))
                .willReturn(true);

        // when
        paymentOutboxUseCase.recordPublishFailureDelay(ORDER_ID);

        // then — FIXED 5000ms 정책이므로 다음 시도 시각은 현재 시각 + 5초
        then(mockPaymentOutboxRepository).should(times(1))
                .recordRetryDelay(ORDER_ID, 0, 1, FIXED_INSTANT.plus(Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("recordPublishFailureDelay: 행이 없으면 조건부 갱신을 호출하지 않고 조용히 넘어간다")
    void recordPublishFailureDelay_noOutbox_skipsQuietly() {
        // given
        given(mockPaymentOutboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatCode(() -> paymentOutboxUseCase.recordPublishFailureDelay(ORDER_ID))
                .doesNotThrowAnyException();
        then(mockPaymentOutboxRepository).should(never())
                .recordRetryDelay(anyString(), anyInt(), anyInt(), any(Instant.class));
    }

    @Test
    @DisplayName("recordPublishFailureDelay: 조건부 갱신이 0건이면(경합 패배) 예외 없이 조용히 넘어간다")
    void recordPublishFailureDelay_conditionalUpdateAffectsZeroRows_skipsQuietly() {
        // given
        PaymentOutbox pendingOutbox = PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(ORDER_ID)
                .status(PaymentOutboxStatus.PENDING)
                .retryCount(0)
                .allArgsBuild();
        given(mockPaymentOutboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(pendingOutbox));
        given(mockPaymentOutboxRepository.recordRetryDelay(eq(ORDER_ID), eq(0), eq(1), any(Instant.class)))
                .willReturn(false);

        // when & then — 다른 워커가 먼저 선점했거나 기록을 마친 정상 흐름, 예외를 던지지 않는다
        assertThatCode(() -> paymentOutboxUseCase.recordPublishFailureDelay(ORDER_ID))
                .doesNotThrowAnyException();
    }
}
