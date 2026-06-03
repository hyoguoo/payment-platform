package com.hyoguoo.paymentplatform.payment.infrastructure.scheduler;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventDedupeStore;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code payment_event_dedupe} 만료 행 청소 스케줄러.
 *
 * <p>D-CLEAN-2/4 구현체. {@link PaymentEventDedupeStore#deleteExpired(Instant, int)} 를 주기적으로 호출해
 * TTL 만료된 dedupe 행을 일괄 삭제한다.
 *
 * <p><b>보존 정책</b>: {@code payment_event_dedupe} 행은 Kafka retention(7일) 이상 유지해야 재배달 멱등에 무해하다.
 * TTL(8일) &gt; Kafka retention(7일) 불변식을 깨는 설정 변경은 금지한다(discuss-domain-2 검토6).
 *
 * <p><b>단일 인스턴스 가정</b>: 만료 조건 삭제는 멱등이라 다중 실행 시 무해하지만, 다중 인스턴스 전환 시
 * 분산 락 추가 검토 필요(CONCERNS L-5 / TODOS TC-13-FOLLOW-1 참고).
 *
 * <p>Micrometer 카운터 {@code payment_event_dedupe.cleanup_deleted_total}: 실제 삭제 행 수를
 * 누적 합산해 cleanup 처리량 관측성을 제공한다. 누적 페이스가 느리면 배치 사이즈 또는 주기 조정 신호.
 *
 * <p>예외 처리: {@link PaymentEventDedupeStore#deleteExpired} 호출 중 예외 발생 시 예외를 전파하지 않고
 * ERROR 로그 후 다음 fixedDelay 주기에 재시도한다(L-1 장애 대응).
 */
@Slf4j
@Component
public class DedupeCleanupWorker {

    /**
     * 삭제된 dedupe 행 수 카운터 이름.
     * cleanup 주기마다 실제 삭제 행 수를 누적 합산한다.
     */
    static final String CLEANUP_DELETED_COUNTER_NAME =
            "payment_event_dedupe.cleanup_deleted_total";

    private final PaymentEventDedupeStore dedupeStore;
    private final Clock clock;
    private final int batchSize;
    private final Counter cleanupDeletedCounter;

    public DedupeCleanupWorker(
            PaymentEventDedupeStore dedupeStore,
            Clock clock,
            @Value("${scheduler.dedupe-cleanup-worker.batch-size:1000}") int batchSize,
            MeterRegistry meterRegistry) {
        this.dedupeStore = dedupeStore;
        this.clock = clock;
        this.batchSize = batchSize;
        this.cleanupDeletedCounter = Counter.builder(CLEANUP_DELETED_COUNTER_NAME)
                .description("payment_event_dedupe 만료 행 삭제 건수 누적 — TTL 초과 행 청소량 관측")
                .register(meterRegistry);
    }

    /**
     * 만료 행 일괄 청소 실행.
     *
     * <p>fixedDelay 방식이라 이전 실행 완료 후 지정 시간이 지난 뒤 다음 실행이 시작된다.
     * 예외 발생 시 전파하지 않고 ERROR 로그 후 다음 주기에 재시도한다.
     */
    @Scheduled(fixedDelayString = "${scheduler.dedupe-cleanup-worker.fixed-delay-ms:3600000}")
    public void cleanup() {
        Instant now = clock.instant();
        deleteExpiredBatch(now);
    }

    private void deleteExpiredBatch(Instant now) {
        int deleted = executeDeleteExpired(now);
        cleanupDeletedCounter.increment(deleted);
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_DEDUPE,
                () -> "dedupe-cleanup deleted=" + deleted + " batchSize=" + batchSize);
    }

    private int executeDeleteExpired(Instant now) {
        try {
            return dedupeStore.deleteExpired(now, batchSize);
        } catch (RuntimeException e) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.EXCEPTION,
                    () -> "dedupe-cleanup 실패 — 다음 주기 재시도. error=" + e.getMessage());
            return 0;
        }
    }
}
