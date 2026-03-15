package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("PaymentOutboxUseCase 테스트")
class PaymentOutboxUseCaseTest {

    private PaymentOutboxRepository mockPaymentOutboxRepository;
    private LocalDateTimeProvider mockLocalDateTimeProvider;
    private PaymentOutboxUseCase paymentOutboxUseCase;

    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 3, 15, 12, 0, 0);
    private static final String ORDER_ID = "order-123";

    @BeforeEach
    void setUp() {
        mockPaymentOutboxRepository = Mockito.mock(PaymentOutboxRepository.class);
        mockLocalDateTimeProvider = Mockito.mock(LocalDateTimeProvider.class);
        paymentOutboxUseCase = new PaymentOutboxUseCase(
                mockPaymentOutboxRepository,
                mockLocalDateTimeProvider
        );
        given(mockLocalDateTimeProvider.now()).willReturn(FIXED_NOW);
    }

    @Test
    @DisplayName("createPendingRecord: save()를 1회 호출하고 PENDING 상태 PaymentOutbox를 반환한다")
    void createPendingRecord_savesAndReturnsPendingOutbox() {
        // given
        PaymentOutbox pendingOutbox = PaymentOutbox.createPending(ORDER_ID);
        given(mockPaymentOutboxRepository.save(any(PaymentOutbox.class))).willReturn(pendingOutbox);

        // when
        PaymentOutbox result = paymentOutboxUseCase.createPendingRecord(ORDER_ID);

        // then
        assertThat(result.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
        assertThat(result.getOrderId()).isEqualTo(ORDER_ID);
        then(mockPaymentOutboxRepository).should(times(1)).save(any(PaymentOutbox.class));
    }

    @Test
    @DisplayName("claimToInFlight - 성공: PENDING 레코드를 IN_FLIGHT으로 전환 후 save() 호출하고 true 반환")
    void claimToInFlight_pendingRecord_savesAndReturnsTrue() {
        // given
        PaymentOutbox pendingOutbox = PaymentOutbox.createPending(ORDER_ID);
        given(mockPaymentOutboxRepository.save(any(PaymentOutbox.class))).willReturn(pendingOutbox);

        // when
        boolean result = paymentOutboxUseCase.claimToInFlight(pendingOutbox);

        // then
        assertThat(result).isTrue();
        then(mockPaymentOutboxRepository).should(times(1)).save(pendingOutbox);
    }

    @Test
    @DisplayName("claimToInFlight - 이미 IN_FLIGHT: save() 호출하지 않고 false 반환")
    void claimToInFlight_alreadyInFlight_returnsFalseWithoutSave() {
        // given
        PaymentOutbox inFlightOutbox = PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(ORDER_ID)
                .status(PaymentOutboxStatus.IN_FLIGHT)
                .retryCount(0)
                .inFlightAt(FIXED_NOW)
                .build();

        // when
        boolean result = paymentOutboxUseCase.claimToInFlight(inFlightOutbox);

        // then
        assertThat(result).isFalse();
        then(mockPaymentOutboxRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("markDone - 멱등: 이미 DONE 상태이면 save() 호출하지 않는다")
    void markDone_alreadyDone_doesNotCallSave() {
        // given
        PaymentOutbox doneOutbox = PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(ORDER_ID)
                .status(PaymentOutboxStatus.DONE)
                .retryCount(0)
                .build();
        given(mockPaymentOutboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(doneOutbox));

        // when
        paymentOutboxUseCase.markDone(ORDER_ID);

        // then
        then(mockPaymentOutboxRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("markDone - 정상: IN_FLIGHT 상태이면 toDone() 후 save() 1회 호출")
    void markDone_inFlight_callsToDoneAndSave() {
        // given
        PaymentOutbox inFlightOutbox = PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(ORDER_ID)
                .status(PaymentOutboxStatus.IN_FLIGHT)
                .retryCount(0)
                .inFlightAt(FIXED_NOW)
                .build();
        given(mockPaymentOutboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(inFlightOutbox));
        given(mockPaymentOutboxRepository.save(any(PaymentOutbox.class))).willReturn(inFlightOutbox);

        // when
        paymentOutboxUseCase.markDone(ORDER_ID);

        // then
        then(mockPaymentOutboxRepository).should(times(1)).save(inFlightOutbox);
        assertThat(inFlightOutbox.getStatus()).isEqualTo(PaymentOutboxStatus.DONE);
    }

    @Test
    @DisplayName("markFailed - 멱등: 이미 FAILED 상태이면 save() 호출하지 않는다")
    void markFailed_alreadyFailed_doesNotCallSave() {
        // given
        PaymentOutbox failedOutbox = PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(ORDER_ID)
                .status(PaymentOutboxStatus.FAILED)
                .retryCount(0)
                .build();
        given(mockPaymentOutboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(failedOutbox));

        // when
        paymentOutboxUseCase.markFailed(ORDER_ID);

        // then
        then(mockPaymentOutboxRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("incrementRetryOrFail - retryable: retryCount=3이면 incrementRetryCount() 후 save() 호출")
    void incrementRetryOrFail_retryable_incrementsAndSaves() {
        // given
        PaymentOutbox retryableOutbox = PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(ORDER_ID)
                .status(PaymentOutboxStatus.IN_FLIGHT)
                .retryCount(3)
                .build();
        given(mockPaymentOutboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(retryableOutbox));
        given(mockPaymentOutboxRepository.save(any(PaymentOutbox.class))).willReturn(retryableOutbox);

        // when
        paymentOutboxUseCase.incrementRetryOrFail(ORDER_ID, retryableOutbox);

        // then
        assertThat(retryableOutbox.getRetryCount()).isEqualTo(4);
        assertThat(retryableOutbox.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
        then(mockPaymentOutboxRepository).should(times(1)).save(retryableOutbox);
    }

    @Test
    @DisplayName("incrementRetryOrFail - limit exceeded: retryCount=5이면 markFailed() 호출")
    void incrementRetryOrFail_limitExceeded_callsMarkFailed() {
        // given
        PaymentOutbox exhaustedOutbox = PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(ORDER_ID)
                .status(PaymentOutboxStatus.IN_FLIGHT)
                .retryCount(5)
                .build();
        given(mockPaymentOutboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(exhaustedOutbox));
        given(mockPaymentOutboxRepository.save(any(PaymentOutbox.class))).willReturn(exhaustedOutbox);

        // when
        paymentOutboxUseCase.incrementRetryOrFail(ORDER_ID, exhaustedOutbox);

        // then
        assertThat(exhaustedOutbox.getStatus()).isEqualTo(PaymentOutboxStatus.FAILED);
        then(mockPaymentOutboxRepository).should(times(1)).save(exhaustedOutbox);
    }

    @Test
    @DisplayName("recoverTimedOutInFlightRecords: findTimedOutInFlight() 결과 각각 incrementRetryCount() + save() 호출")
    void recoverTimedOutInFlightRecords_callsIncrementAndSaveForEach() {
        // given
        PaymentOutbox outbox1 = PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId("order-001")
                .status(PaymentOutboxStatus.IN_FLIGHT)
                .retryCount(1)
                .inFlightAt(FIXED_NOW.minusMinutes(10))
                .build();
        PaymentOutbox outbox2 = PaymentOutbox.allArgsBuilder()
                .id(2L)
                .orderId("order-002")
                .status(PaymentOutboxStatus.IN_FLIGHT)
                .retryCount(2)
                .inFlightAt(FIXED_NOW.minusMinutes(10))
                .build();

        given(mockPaymentOutboxRepository.findTimedOutInFlight(any(LocalDateTime.class)))
                .willReturn(List.of(outbox1, outbox2));
        given(mockPaymentOutboxRepository.save(any(PaymentOutbox.class))).willReturn(outbox1);

        // when
        paymentOutboxUseCase.recoverTimedOutInFlightRecords(5);

        // then
        assertThat(outbox1.getRetryCount()).isEqualTo(2);
        assertThat(outbox1.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
        assertThat(outbox2.getRetryCount()).isEqualTo(3);
        assertThat(outbox2.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
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
                .build();
        given(mockPaymentOutboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(doneOutbox));

        // when
        Optional<PaymentOutboxStatus> result = paymentOutboxUseCase.findActiveOutboxStatus(ORDER_ID);

        // then
        assertThat(result).isEmpty();
    }
}
