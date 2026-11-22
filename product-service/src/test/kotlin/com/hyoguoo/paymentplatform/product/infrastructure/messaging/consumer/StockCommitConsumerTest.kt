package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer

import com.hyoguoo.paymentplatform.product.application.usecase.StockCommitUseCase
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer.dto.StockCommittedMessage
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * StockCommitConsumer 단위 테스트.
 *
 * Mock StockCommitUseCase — 메시지 파싱·위임 검증.
 * Clock 주입으로 expiresAt=null, occurredAt=null 케이스를 결정적으로 제어한다.
 * consume 진입 시 단일 now = clock.instant() 산출 — commit 인자와 resolveExpiresAt fallback base가 동일 Instant 공유.
 */
class StockCommitConsumerTest {
    private lateinit var stockCommitUseCase: StockCommitUseCase
    private lateinit var stockCommitConsumer: StockCommitConsumer

    @BeforeEach
    fun setUp() {
        // Mockito 목처럼 반환 없는 호출을 허용한다. 호출 인자는 verify 로 확인한다.
        stockCommitUseCase = mockk(relaxed = true)
        stockCommitConsumer = StockCommitConsumer(FIXED_CLOCK, stockCommitUseCase)
    }

    @Test
    @DisplayName("consume 호출 시 StockCommitUseCase.commit 1회 위임")
    fun consume_ShouldDelegateToStockCommitUseCase() {
        // given
        val productId = 10L
        val orderId = "order-1000" // orderId 는 String 으로 통일
        val eventUUID = "event-uuid-consumer-test"
        val qty = 7
        val occurredAt = Instant.now()
        val expiresAt = occurredAt.plusSeconds(86400)

        val message =
            StockCommittedMessage(
                productId,
                qty,
                eventUUID,
                occurredAt,
                orderId,
                expiresAt,
            )

        // when
        stockCommitConsumer.consume(message)

        // then: usecase 1회 위임 — orderId 는 String 그대로 전달된다.
        // now 인자는 consume() 진입 시 clock.instant()로 산출 — FIXED_CLOCK 이므로 FIXED_INSTANT
        verify(exactly = 1) {
            stockCommitUseCase.commit(eventUUID, orderId, productId, qty, FIXED_INSTANT, expiresAt)
        }
    }

    @Test
    @DisplayName("expiresAt=null, occurredAt=fixed → expiresAt = occurredAt + 8d 로 fallback")
    fun consume_whenExpiresAtNull_shouldFallbackFromOccurredAt() {
        // given
        val productId = 10L
        val orderId = "order-1000" // orderId 는 String 으로 통일
        val eventUUID = "event-uuid-fallback-test"
        val qty = 3
        val occurredAt = Instant.parse("2026-01-01T00:00:00Z")

        val message =
            StockCommittedMessage(
                productId,
                qty,
                eventUUID,
                occurredAt,
                orderId,
                null, // expiresAt null — Producer 미전송 케이스
            )

        // when
        stockCommitConsumer.consume(message)

        // then: expiresAt = occurredAt + DEDUPE_TTL(8d), now = FIXED_INSTANT (clock.instant())
        val expiresAtCaptor = slot<Instant>()
        verify(exactly = 1) {
            stockCommitUseCase.commit(
                eventUUID,
                orderId,
                productId,
                qty,
                FIXED_INSTANT,
                capture(expiresAtCaptor),
            )
        }

        val expectedExpiresAt = occurredAt.plus(StockCommitUseCase.DEDUPE_TTL)
        assertThat(expiresAtCaptor.captured).isEqualTo(expectedExpiresAt)
    }

    /**
     * RED 테스트: clock.instant() 가 호출마다 1ms씩 전진하는 tick Clock 을 주입했을 때,
     * expiresAt=null/occurredAt=null 케이스에서 commit 의 now 인자와 resolveExpiresAt fallback base 가
     * 동일 Instant 여야 한다 (단일 진입점 동일 시각).
     * 현재 구현(resolveExpiresAt 내부에서 clock.instant() 별도 호출)은 now != fallback base 가 되어 FAIL.
     */
    @Test
    @DisplayName(
        "consume_clock주입now_useCaseCommit에전달: 단일 now 산출 — commit now 인자와 resolveExpiresAt fallback base가 동일 Instant",
    )
    fun `consume_clock주입now_useCaseCommit에전달`() {
        // given — 호출마다 1ms 전진하는 tick Clock
        val tickCount = AtomicInteger(0)
        val tickClock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId?): Clock = this

                override fun instant(): Instant =
                    FIXED_INSTANT.plus(Duration.ofMillis(tickCount.getAndIncrement().toLong()))
            }
        val tickConsumer = StockCommitConsumer(tickClock, stockCommitUseCase)

        // expiresAt=null, occurredAt=null → resolveExpiresAt 내부에서 clock.instant() 호출 경로 진입
        val message =
            StockCommittedMessage(
                10L,
                1,
                "event-uuid-p5",
                null, // occurredAt null
                "order-p5",
                null, // expiresAt null
            )

        // when
        tickConsumer.consume(message)

        // then: commit 에 전달된 now 와 expiresAt - DEDUPE_TTL (= fallback base) 가 동일 Instant 여야 한다.
        // 단일 산출이면 now == fallback base → expiresAt = now + DEDUPE_TTL → expiresAt - DEDUPE_TTL == now
        val nowCaptor = slot<Instant>()
        val expiresAtCaptor = slot<Instant>()
        verify(exactly = 1) {
            stockCommitUseCase.commit(
                "event-uuid-p5",
                "order-p5",
                10L,
                1,
                capture(nowCaptor),
                capture(expiresAtCaptor),
            )
        }

        val capturedNow = nowCaptor.captured
        val capturedExpiresAt = expiresAtCaptor.captured
        val fallbackBase = capturedExpiresAt.minus(StockCommitUseCase.DEDUPE_TTL)
        assertThat(fallbackBase).isEqualTo(capturedNow)
    }

    @Test
    @DisplayName("expiresAt=null, occurredAt=null → expiresAt = clock.instant() + 8d (고정 시각 기반)")
    fun consume_whenBothNull_shouldFallbackFromClock() {
        // given — Clock.fixed 주입으로 fallback 시각을 결정적으로 제어
        val productId = 10L
        val orderId = "order-1000"
        val eventUUID = "event-uuid-bothnull-test"
        val qty = 1

        val message =
            StockCommittedMessage(
                productId,
                qty,
                eventUUID,
                null, // occurredAt null
                orderId,
                null, // expiresAt null
            )

        // when
        stockCommitConsumer.consume(message)

        // then: expiresAt = FIXED_INSTANT + DEDUPE_TTL (clock.instant() 기반으로 결정적), now = FIXED_INSTANT
        val expiresAtCaptor = slot<Instant>()
        verify(exactly = 1) {
            stockCommitUseCase.commit(
                eventUUID,
                orderId,
                productId,
                qty,
                FIXED_INSTANT,
                capture(expiresAtCaptor),
            )
        }

        val expectedExpiresAt = FIXED_INSTANT.plus(StockCommitUseCase.DEDUPE_TTL)
        assertThat(expiresAtCaptor.captured).isEqualTo(expectedExpiresAt)
    }

    private companion object {
        val FIXED_INSTANT: Instant = Instant.parse("2026-01-01T12:00:00Z")
        val FIXED_CLOCK: Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)
    }
}
