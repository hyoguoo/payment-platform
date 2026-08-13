package com.hyoguoo.paymentplatform.pg.core.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * pg-service 격리(QUARANTINED) 도달 카운터.
 *
 * <p>{@link com.hyoguoo.paymentplatform.pg.application.service.PgFinalConfirmationGate#applyOutcome}
 * 이 격리 결과를 반영하는 지점({@code handleQuarantined} 내부)에서, {@code transitToQuarantined}
 * 가 true(non-terminal → QUARANTINED CAS 성공)를 반환할 때 호출된다. 대기열 소비
 * ({@link com.hyoguoo.paymentplatform.pg.application.service.PgDlqService#handle})는 격리 직행
 * 대신 이 관문에 위임하므로, 멱등 보장 지점도 관문의 CAS 전이로 함께 옮겨졌다. terminal CAS 는
 * 1회만 통과하므로 같은 orderId 의 중복 진입은 이 카운터를 증가시키지 않는다 — 멱등.
 *
 * <p>증가 시점을 시도횟수 소진({@code insertDlqOutbox}) 이 아니라 QUARANTINED 전이
 * 성공 지점으로 둔 이유: 소진 후 QUARANTINED 전이 전까지 좀비 재호출 window 에서
 * DLQ outbox 가 중복 INSERT 될 수 있어(over-count), 멱등이 보장되는 전이 시점이
 * alert 임계 기준으로 정확하다(결정 노트 참조).
 *
 * <p>라벨 없음(단일 카운터) — 발생 빈도 추적이 목적이라 고카디널리티 라벨을 두지 않는다.
 *
 * <p>{@link #record} 는 throw-free 계약을 유지한다. Micrometer Counter.increment() 자체는
 * 안전하다.
 *
 * <p>Eager 등록: 생성자에서 카운터를 0으로 사전 등록한다 — 기동 직후 Prometheus 스크레이프에
 * "No data" 없이 0 시리즈가 노출됨.
 */
@Component
public class PgDlqReachMetrics {

    private static final String METRIC_NAME = "pg_retry_exhausted_quarantine_total";

    private final Counter quarantineReachedCounter;

    public PgDlqReachMetrics(MeterRegistry meterRegistry) {
        this.quarantineReachedCounter = Counter.builder(METRIC_NAME)
                .description("Total number of pg_inbox rows reaching QUARANTINED after retry exhaustion")
                .register(meterRegistry);
    }

    /**
     * 격리 도달 카운터를 증가시킨다.
     */
    public void record() {
        quarantineReachedCounter.increment();
    }
}
