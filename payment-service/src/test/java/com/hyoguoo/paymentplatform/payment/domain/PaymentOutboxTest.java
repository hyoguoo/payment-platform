package com.hyoguoo.paymentplatform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyoguoo.paymentplatform.payment.domain.enums.BackoffType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("PaymentOutbox 도메인 테스트")
class PaymentOutboxTest {

    @Nested
    @DisplayName("PENDING 레코드 생성 테스트")
    class CreatePendingTest {

        @Test
        @DisplayName("주문 ID로 PENDING 레코드를 생성하면 status=PENDING, retryCount=0, inFlightAt=null을 가진다")
        void createPending() {
            // given
            String orderId = "order-1";

            // when
            PaymentOutbox outbox = PaymentOutbox.createPending(orderId);

            // then
            assertThat(outbox.getOrderId()).isEqualTo(orderId);
            assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
            assertThat(outbox.getRetryCount()).isZero();
            assertThat(outbox.getInFlightAt()).isNull();
        }
    }

    @Nested
    @DisplayName("IN_FLIGHT 상태 전이 테스트")
    class ToInFlightTest {

        @ParameterizedTest
        @EnumSource(value = PaymentOutboxStatus.class, names = {"PENDING"})
        @DisplayName("PENDING 상태에서 toInFlight() 호출 시 status=IN_FLIGHT, inFlightAt이 설정된다")
        void toInFlight_Success(PaymentOutboxStatus initialStatus) {
            // given
            PaymentOutbox outbox = createOutboxWithStatus(initialStatus);
            LocalDateTime now = LocalDateTime.now();

            // when
            outbox.toInFlight(now);

            // then
            assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.IN_FLIGHT);
            assertThat(outbox.getInFlightAt()).isEqualTo(now);
        }

        @ParameterizedTest
        @EnumSource(value = PaymentOutboxStatus.class, names = {"IN_FLIGHT", "DONE", "FAILED"})
        @DisplayName("PENDING이 아닌 상태에서 toInFlight() 호출 시 PaymentStatusException이 발생한다")
        void toInFlight_InvalidState(PaymentOutboxStatus initialStatus) {
            // given
            PaymentOutbox outbox = createOutboxWithStatus(initialStatus);
            LocalDateTime now = LocalDateTime.now();

            // when & then
            assertThatThrownBy(() -> outbox.toInFlight(now))
                    .isInstanceOf(PaymentStatusException.class);
        }
    }

    @Nested
    @DisplayName("재시도 카운트 증가 테스트")
    class IncrementRetryCountTest {

        @Test
        @DisplayName("incrementRetryCount(policy, now) 호출 시 retryCount+1, status=PENDING으로 복귀한다")
        void incrementRetryCount() {
            // given
            PaymentOutbox outbox = createOutboxWithStatus(PaymentOutboxStatus.FAILED);
            int initialRetryCount = outbox.getRetryCount();
            RetryPolicy policy = new RetryPolicy(5, BackoffType.FIXED, 5000L, 60000L);

            // when
            outbox.incrementRetryCount(policy, LocalDateTime.now());

            // then
            assertThat(outbox.getRetryCount()).isEqualTo(initialRetryCount + 1);
            assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
        }
    }

    @Nested
    @DisplayName("RetryPolicy 기반 재시도 카운트 증가 테스트")
    class IncrementRetryCountWithPolicyTest {

        @Test
        @DisplayName("FIXED backoff 정책으로 incrementRetryCount 시 nextRetryAt이 now + baseDelayMs로 설정된다")
        void incrementRetryCount_FIXED_nextRetryAt_설정() {
            // given
            PaymentOutbox outbox = createOutboxWithStatus(PaymentOutboxStatus.IN_FLIGHT);
            RetryPolicy policy = new RetryPolicy(5, BackoffType.FIXED, 5000L, 60000L);
            LocalDateTime now = LocalDateTime.of(2024, 1, 1, 0, 0, 0);

            // when
            outbox.incrementRetryCount(policy, now);

            // then
            assertThat(outbox.getNextRetryAt()).isEqualTo(now.plusSeconds(5));
        }

        @Test
        @DisplayName("EXPONENTIAL backoff 정책으로 incrementRetryCount 시 nextRetryAt이 지수적으로 증가한다")
        void incrementRetryCount_EXPONENTIAL_nextRetryAt_지수_증가() {
            // given
            PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                    .orderId("order-1")
                    .status(PaymentOutboxStatus.IN_FLIGHT)
                    .retryCount(2)
                    .allArgsBuild();
            RetryPolicy policy = new RetryPolicy(5, BackoffType.EXPONENTIAL, 1000L, 60000L);
            LocalDateTime now = LocalDateTime.of(2024, 1, 1, 0, 0, 0);

            // when
            outbox.incrementRetryCount(policy, now);

            // then
            // retryCount=2에서 호출, 증가 후 retryCount=3, nextDelay = 1000 * 2^3 = 8000ms
            assertThat(outbox.getNextRetryAt()).isEqualTo(now.plusSeconds(8));
        }

        @Test
        @DisplayName("incrementRetryCount(RetryPolicy, now) 호출 시 retryCount+1, status=PENDING으로 복귀한다")
        void incrementRetryCount_RetryPolicy_retryCount_증가_및_PENDING_복원() {
            // given
            PaymentOutbox outbox = createOutboxWithStatus(PaymentOutboxStatus.IN_FLIGHT);
            RetryPolicy policy = new RetryPolicy(5, BackoffType.FIXED, 5000L, 60000L);
            int initialRetryCount = outbox.getRetryCount();

            // when
            outbox.incrementRetryCount(policy, LocalDateTime.now());

            // then
            assertThat(outbox.getRetryCount()).isEqualTo(initialRetryCount + 1);
            assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
        }
    }

    @Nested
    @DisplayName("DONE/FAILED 상태 전이 테스트")
    class TerminalStateTest {

        @ParameterizedTest
        @EnumSource(value = PaymentOutboxStatus.class, names = {"IN_FLIGHT"})
        @DisplayName("IN_FLIGHT 상태에서 toDone() 호출 시 status=DONE이 된다")
        void toDone_Success(PaymentOutboxStatus initialStatus) {
            // given
            PaymentOutbox outbox = createOutboxWithStatus(initialStatus);

            // when
            outbox.toDone();

            // then
            assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.DONE);
        }

        @ParameterizedTest
        @EnumSource(value = PaymentOutboxStatus.class, names = {"PENDING", "DONE", "FAILED"})
        @DisplayName("IN_FLIGHT이 아닌 상태에서 toDone() 호출 시 PaymentStatusException이 발생한다")
        void toDone_InvalidState(PaymentOutboxStatus initialStatus) {
            // given
            PaymentOutbox outbox = createOutboxWithStatus(initialStatus);

            // when & then
            assertThatThrownBy(outbox::toDone)
                    .isInstanceOf(PaymentStatusException.class);
        }

        @ParameterizedTest
        @EnumSource(value = PaymentOutboxStatus.class, names = {"IN_FLIGHT"})
        @DisplayName("IN_FLIGHT 상태에서 toFailed() 호출 시 status=FAILED가 된다")
        void toFailed_Success(PaymentOutboxStatus initialStatus) {
            // given
            PaymentOutbox outbox = createOutboxWithStatus(initialStatus);

            // when
            outbox.toFailed();

            // then
            assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.FAILED);
        }

        @ParameterizedTest
        @EnumSource(value = PaymentOutboxStatus.class, names = {"PENDING", "DONE", "FAILED"})
        @DisplayName("IN_FLIGHT이 아닌 상태에서 toFailed() 호출 시 PaymentStatusException이 발생한다")
        void toFailed_InvalidState(PaymentOutboxStatus initialStatus) {
            // given
            PaymentOutbox outbox = createOutboxWithStatus(initialStatus);

            // when & then
            assertThatThrownBy(outbox::toFailed)
                    .isInstanceOf(PaymentStatusException.class);
        }
    }

    // T1-04: 스펙 지정 테스트 메서드

    @Nested
    @DisplayName("T1-04 스펙 지정 전이 테스트")
    class T1Spec04Test {

        @Test
        @DisplayName("toDone_ChangesStatusToProcessed: IN_FLIGHT 상태에서 toDone() 호출 시 status=DONE으로 변경된다.")
        void toDone_ChangesStatusToProcessed() {
            // given
            PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                    .orderId("order-spec-04")
                    .status(PaymentOutboxStatus.IN_FLIGHT)
                    .retryCount(0)
                    .allArgsBuild();

            // when
            outbox.toDone();

            // then
            assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.DONE);
        }

        @Test
        @DisplayName("nextRetryAt_ComputedCorrectly_ForExponentialBackoff: EXPONENTIAL 정책으로 nextRetryAt이 2^n * baseDelay로 계산된다.")
        void nextRetryAt_ComputedCorrectly_ForExponentialBackoff() {
            // given — retryCount=1에서 시작, 증가 후 retryCount=2 → delay = 1000 * 2^2 = 4000ms
            PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                    .orderId("order-spec-04-exp")
                    .status(PaymentOutboxStatus.IN_FLIGHT)
                    .retryCount(1)
                    .allArgsBuild();
            RetryPolicy policy = new RetryPolicy(5, BackoffType.EXPONENTIAL, 1000L, 60000L);
            LocalDateTime now = LocalDateTime.of(2024, 6, 1, 0, 0, 0);

            // when
            outbox.incrementRetryCount(policy, now);

            // then — retryCount=2, nextDelay = 1000 * 2^2 = 4000ms = 4초
            assertThat(outbox.getRetryCount()).isEqualTo(2);
            assertThat(outbox.getNextRetryAt()).isEqualTo(now.plusSeconds(4));
        }
    }

    private PaymentOutbox createOutboxWithStatus(PaymentOutboxStatus status) {
        return PaymentOutbox.allArgsBuilder()
                .orderId("order-1")
                .status(status)
                .retryCount(0)
                .allArgsBuild();
    }
}
