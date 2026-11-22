package com.hyoguoo.paymentplatform.product.infrastructure.scheduler

import com.hyoguoo.paymentplatform.product.application.port.out.EventDedupeStore
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * DedupeCleanupWorker 단위 테스트 — MockK 기반.
 *
 * 검증 대상:
 * - deleteExpired 1회 호출 + Micrometer counter 증가
 * - deleteExpired 예외 시 예외 전파 없이 다음 주기 재시도
 * - deleteExpired 예외 시 cleanup_failed_total 카운터 1 증가, cleanup_deleted_total 0 유지
 * - 정상 수행 시 cleanup_failed_total 카운터 미증가
 */
@DisplayName("DedupeCleanupWorker 테스트")
class DedupeCleanupWorkerTest {
    private lateinit var mockDedupeStore: EventDedupeStore
    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var worker: DedupeCleanupWorker

    @BeforeEach
    fun setUp() {
        mockDedupeStore = mockk()
        meterRegistry = SimpleMeterRegistry()
        worker = DedupeCleanupWorker(FIXED_CLOCK, mockDedupeStore, 1000, meterRegistry)
    }

    @Test
    @DisplayName("cleanup - deleteExpired가 4를 반환하면 counter가 4 증가한다")
    fun `cleanup_삭제행수_카운터증가`() {
        // given
        every { mockDedupeStore.deleteExpired(any(), any()) } returns 4

        // when
        worker.cleanup()

        // then
        verify(exactly = 1) { mockDedupeStore.deleteExpired(any(), any()) }

        val counter = meterRegistry.find(COUNTER_NAME).counter()
        assertThat(counter).isNotNull
        assertThat(counter!!.count()).isEqualTo(4.0)
    }

    @Test
    @DisplayName("cleanup - deleteExpired가 RuntimeException을 던져도 예외가 밖으로 전파되지 않는다")
    fun `cleanup_예외시_전파하지않음`() {
        // given
        every { mockDedupeStore.deleteExpired(any(), any()) } throws RuntimeException("DB 장애 시뮬레이션")

        // when & then
        assertThatCode { worker.cleanup() }.doesNotThrowAnyException()
    }

    @Test
    @DisplayName(
        "cleanup - deleteExpired가 RuntimeException을 던지면 cleanup_failed_total 카운터가 1 증가하고 cleanup_deleted_total은 0이다",
    )
    fun `cleanup_예외발생시_failedCounter증가`() {
        // given
        every { mockDedupeStore.deleteExpired(any(), any()) } throws RuntimeException("DB 장애 시뮬레이션")

        // when
        worker.cleanup()

        // then
        val failedCounter = meterRegistry.find(FAILED_COUNTER_NAME).counter()
        assertThat(failedCounter).isNotNull
        assertThat(failedCounter!!.count()).isEqualTo(1.0)

        val deletedCounter = meterRegistry.find(COUNTER_NAME).counter()
        assertThat(deletedCounter).isNotNull
        assertThat(deletedCounter!!.count()).isEqualTo(0.0)
    }

    @Test
    @DisplayName("cleanup - deleteExpired가 3을 반환하면 cleanup_failed_total 카운터는 0이다")
    fun `cleanup_정상수행시_failedCounter미증가`() {
        // given
        every { mockDedupeStore.deleteExpired(any(), any()) } returns 3

        // when
        worker.cleanup()

        // then
        val failedCounter = meterRegistry.find(FAILED_COUNTER_NAME).counter()
        assertThat(failedCounter).isNotNull
        assertThat(failedCounter!!.count()).isEqualTo(0.0)

        val deletedCounter = meterRegistry.find(COUNTER_NAME).counter()
        assertThat(deletedCounter).isNotNull
        assertThat(deletedCounter!!.count()).isEqualTo(3.0)
    }

    private companion object {
        const val COUNTER_NAME = "stock_commit_dedupe.cleanup_deleted_total"
        const val FAILED_COUNTER_NAME = "stock_commit_dedupe.cleanup_failed_total"

        val FIXED_INSTANT: Instant = Instant.parse("2026-01-01T12:00:00Z")
        val FIXED_CLOCK: Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)
    }
}
