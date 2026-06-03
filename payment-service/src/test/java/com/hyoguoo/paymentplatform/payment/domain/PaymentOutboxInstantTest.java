package com.hyoguoo.paymentplatform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyoguoo.paymentplatform.payment.domain.enums.BackoffType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("PaymentOutbox 도메인 Instant 전환 테스트")
class PaymentOutboxInstantTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-06-01T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    @Nested
    @DisplayName("toInFlight(Instant) — Instant 인자 전이 테스트")
    class ToInFlightInstantTest {

        @Test
        @DisplayName("PENDING 상태에서 toInFlight(Instant) 호출 시 status=IN_FLIGHT, inFlightAt=fixedInstant 단정")
        void toInFlight_withInstant_setsInFlightAt() {
            // given
            PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                    .orderId("order-instant-01")
                    .status(PaymentOutboxStatus.PENDING)
                    .retryCount(0)
                    .allArgsBuild();

            // when
            outbox.toInFlight(FIXED_INSTANT);

            // then
            assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.IN_FLIGHT);
            assertThat(outbox.getInFlightAt()).isEqualTo(FIXED_INSTANT);
        }

        @ParameterizedTest
        @EnumSource(value = PaymentOutboxStatus.class, names = {"IN_FLIGHT", "DONE", "FAILED"})
        @DisplayName("PENDING이 아닌 상태에서 toInFlight(Instant) 호출 시 PaymentStatusException 발생")
        void toInFlight_withInstant_whenNotPending_shouldThrow(PaymentOutboxStatus initialStatus) {
            // given
            PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                    .orderId("order-instant-01")
                    .status(initialStatus)
                    .retryCount(0)
                    .allArgsBuild();

            // when & then
            assertThatThrownBy(() -> outbox.toInFlight(FIXED_INSTANT))
                    .isInstanceOf(PaymentStatusException.class);
        }
    }

    @Nested
    @DisplayName("incrementRetryCount(RetryPolicy, Instant) — Instant 기반 nextRetryAt 계산 테스트")
    class IncrementRetryCountInstantTest {

        @Test
        @DisplayName("FIXED backoff: incrementRetryCount(policy, Instant) 시 nextRetryAt = fixedInstant + baseDelayMs")
        void incrementRetryCount_FIXED_withInstant_setsNextRetryAt() {
            // given
            PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                    .orderId("order-instant-02")
                    .status(PaymentOutboxStatus.IN_FLIGHT)
                    .retryCount(0)
                    .allArgsBuild();
            RetryPolicy policy = new RetryPolicy(5, BackoffType.FIXED, 5000L, 60000L);

            // when
            outbox.incrementRetryCount(policy, FIXED_INSTANT);

            // then
            assertThat(outbox.getRetryCount()).isEqualTo(1);
            assertThat(outbox.getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
            assertThat(outbox.getNextRetryAt()).isEqualTo(FIXED_INSTANT.plus(Duration.ofMillis(5000L)));
        }

        @Test
        @DisplayName("EXPONENTIAL backoff: incrementRetryCount(policy, Instant) 시 nextRetryAt 지수 증가")
        void incrementRetryCount_EXPONENTIAL_withInstant_exponentialBackoff() {
            // given — retryCount=2에서 시작, 호출 후 retryCount=3, delay = 1000 * 2^3 = 8000ms
            PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                    .orderId("order-instant-03")
                    .status(PaymentOutboxStatus.IN_FLIGHT)
                    .retryCount(2)
                    .allArgsBuild();
            RetryPolicy policy = new RetryPolicy(5, BackoffType.EXPONENTIAL, 1000L, 60000L);

            // when
            outbox.incrementRetryCount(policy, FIXED_INSTANT);

            // then
            assertThat(outbox.getRetryCount()).isEqualTo(3);
            assertThat(outbox.getNextRetryAt()).isEqualTo(FIXED_INSTANT.plus(Duration.ofMillis(8000L)));
        }

        @ParameterizedTest
        @EnumSource(value = PaymentOutboxStatus.class, names = {"PENDING", "DONE", "FAILED"})
        @DisplayName("IN_FLIGHT이 아닌 상태에서 incrementRetryCount(policy, Instant) 호출 시 PaymentStatusException 발생")
        void incrementRetryCount_withInstant_whenNotInFlight_shouldThrow(PaymentOutboxStatus initialStatus) {
            // given
            PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                    .orderId("order-instant-04")
                    .status(initialStatus)
                    .retryCount(0)
                    .allArgsBuild();
            RetryPolicy policy = new RetryPolicy(5, BackoffType.FIXED, 5000L, 60000L);

            // when & then
            assertThatThrownBy(() -> outbox.incrementRetryCount(policy, FIXED_INSTANT))
                    .isInstanceOf(PaymentStatusException.class);
        }
    }

    @Nested
    @DisplayName("allArgsBuilder Instant 필드 — createdAt/updatedAt/nextRetryAt/inFlightAt 단정")
    class AllArgsBuilderInstantFieldTest {

        @Test
        @DisplayName("allArgsBuilder로 Instant 시각 필드를 설정하면 getter가 동일 Instant를 반환한다")
        void allArgsBuilder_withInstantFields_gettersReturnSameInstant() {
            // given
            Instant created = FIXED_INSTANT;
            Instant updated = FIXED_INSTANT.plus(Duration.ofSeconds(1));
            Instant nextRetry = FIXED_INSTANT.plus(Duration.ofSeconds(5));
            Instant inFlight = FIXED_INSTANT.plus(Duration.ofSeconds(2));

            // when
            PaymentOutbox outbox = PaymentOutbox.allArgsBuilder()
                    .orderId("order-instant-05")
                    .status(PaymentOutboxStatus.PENDING)
                    .retryCount(0)
                    .createdAt(created)
                    .updatedAt(updated)
                    .nextRetryAt(nextRetry)
                    .inFlightAt(inFlight)
                    .allArgsBuild();

            // then
            assertThat(outbox.getCreatedAt()).isEqualTo(created);
            assertThat(outbox.getUpdatedAt()).isEqualTo(updated);
            assertThat(outbox.getNextRetryAt()).isEqualTo(nextRetry);
            assertThat(outbox.getInFlightAt()).isEqualTo(inFlight);
        }
    }
}
