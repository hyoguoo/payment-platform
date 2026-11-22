package com.hyoguoo.paymentplatform.product.infrastructure.scheduler

import com.hyoguoo.paymentplatform.product.application.port.out.EventDedupeStore
import com.hyoguoo.paymentplatform.product.core.common.log.EventType
import com.hyoguoo.paymentplatform.product.core.common.log.LogDomain
import com.hyoguoo.paymentplatform.product.core.common.log.LogFmt
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * `stock_commit_dedupe` 만료 행 청소 스케줄러.
 *
 * D-CLEAN-2/4 구현체. [EventDedupeStore.deleteExpired] 를 주기적으로 호출해
 * TTL 만료된 dedupe 행을 일괄 삭제한다.
 *
 * **단일 인스턴스 가정**: 만료 조건 삭제는 멱등이라 다중 실행 시 무해하지만, 다중 인스턴스 전환 시
 * 분산 락 추가 검토 필요.
 *
 * Micrometer 카운터 `stock_commit_dedupe.cleanup_deleted_total`: 실제 삭제 행 수를
 * 누적 합산해 cleanup 처리량 관측성을 제공한다. 누적 페이스가 느리면 배치 사이즈 또는 주기 조정 신호.
 *
 * Micrometer 카운터 `stock_commit_dedupe.cleanup_failed_total`: deleteExpired 호출 중
 * RuntimeException 발생 시 1 증가해 청소 실패 빈도 관측성을 제공한다.
 *
 * 예외 처리: [EventDedupeStore.deleteExpired] 호출 중 예외 발생 시 예외를 전파하지 않고
 * ERROR 로그 후 다음 fixedDelay 주기에 재시도한다(장애 대응).
 */
@Component
class DedupeCleanupWorker(
    private val clock: Clock,
    private val dedupeStore: EventDedupeStore,
    @Value("\${scheduler.dedupe-cleanup-worker.batch-size:1000}")
    private val batchSize: Int,
    meterRegistry: MeterRegistry,
) {
    private val cleanupDeletedCounter: Counter =
        Counter
            .builder(CLEANUP_DELETED_COUNTER_NAME)
            .description("stock_commit_dedupe 만료 행 삭제 건수 누적 — TTL 초과 행 청소량 관측")
            .register(meterRegistry)

    private val cleanupFailedCounter: Counter =
        Counter
            .builder(CLEANUP_FAILED_COUNTER_NAME)
            .description("stock_commit_dedupe 만료 행 삭제 실패 횟수 — deleteExpired 예외 발생 시 증가")
            .register(meterRegistry)

    /**
     * 만료 행 일괄 청소 실행.
     *
     * fixedDelay 방식이라 이전 실행 완료 후 지정 시간이 지난 뒤 다음 실행이 시작된다.
     * 예외 발생 시 전파하지 않고 ERROR 로그 후 다음 주기에 재시도한다.
     */
    @Scheduled(fixedDelayString = "\${scheduler.dedupe-cleanup-worker.fixed-delay-ms:3600000}")
    fun cleanup() {
        // Instant.now() 직접 호출 대신 Clock 주입으로 시각 소스 교체
        deleteExpiredBatch(clock.instant())
    }

    private fun deleteExpiredBatch(now: Instant) {
        val deleted = executeDeleteExpired(now)
        cleanupDeletedCounter.increment(deleted.toDouble())
        LogFmt.info(log, LogDomain.STOCK, EventType.EVENT_DEDUPE_CLEANUP) {
            "dedupe-cleanup deleted=$deleted batchSize=$batchSize"
        }
    }

    // 청소 실패는 어떤 RuntimeException 이든 로그와 실패 카운터로 남기고 다음 주기에 다시 시도한다(이관 전 동작 유지)
    @Suppress("TooGenericExceptionCaught")
    private fun executeDeleteExpired(now: Instant): Int =
        try {
            dedupeStore.deleteExpired(now, batchSize)
        } catch (e: RuntimeException) {
            LogFmt.error(log, LogDomain.STOCK, EventType.EXCEPTION) {
                "dedupe-cleanup 실패 — 다음 주기 재시도. error=${e.message}"
            }
            cleanupFailedCounter.increment()
            0
        }

    companion object {
        /**
         * 삭제된 dedupe 행 수 카운터 이름.
         * cleanup 주기마다 실제 삭제 행 수를 누적 합산한다.
         */
        internal const val CLEANUP_DELETED_COUNTER_NAME = "stock_commit_dedupe.cleanup_deleted_total"

        /**
         * 삭제 실패(예외 발생) 횟수 카운터 이름.
         * deleteExpired 호출 중 RuntimeException 발생 시 1 증가한다.
         */
        internal const val CLEANUP_FAILED_COUNTER_NAME = "stock_commit_dedupe.cleanup_failed_total"

        private val log = LoggerFactory.getLogger(DedupeCleanupWorker::class.java)
    }
}
