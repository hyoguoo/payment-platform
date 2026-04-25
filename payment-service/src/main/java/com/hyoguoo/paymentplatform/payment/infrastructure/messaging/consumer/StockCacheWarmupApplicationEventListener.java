package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.service.StockCacheWarmupService;
import com.hyoguoo.paymentplatform.payment.application.dto.event.StockSnapshotEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * ApplicationReadyEvent 훅 — 재고 캐시 warmup 트리거.
 *
 * <p>Phase-3.1(T3 계열)에서 product-service의 snapshot 발행 훅이 구현될 때까지
 * 빈 토픽으로 처리되어 경고 로그 후 warmup 완료 처리된다.
 *
 * <p>실제 snapshot은 Kafka {@link StockSnapshotWarmupConsumer}가 실시간으로 수신한다.
 * 이 리스너는 기동 시점 일괄 초기화용이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockCacheWarmupApplicationEventListener {

    private final StockCacheWarmupService warmupService;

    /**
     * 애플리케이션 준비 완료 시 warmup을 트리거한다.
     *
     * <p>Phase 1: snapshot 토픽이 아직 비어 있으므로 빈 목록으로 호출 → 경고 로그 후 완료 처리.
     * Phase 3+: product-service에서 snapshot이 발행되면 이 흐름 대신
     * {@link StockSnapshotWarmupConsumer}가 실시간으로 처리하게 된다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SKIPPED,
                () -> "StockCacheWarmup: ApplicationReadyEvent 수신 — warmup 시작");

        // Phase 1: product-service snapshot 발행 훅(T3-01)이 없으므로 빈 목록으로 warmup
        List<StockSnapshotEvent> snapshots = collectSnapshotsFromTopic();
        warmupService.applySnapshots(snapshots);
    }

    /**
     * snapshot 토픽에서 메시지를 수집한다.
     *
     * <p>Phase 1에서는 빈 목록을 반환한다(토픽이 아직 비어 있음).
     * Phase 3+에서는 KafkaConsumer를 통해 토픽 전체를 replay하는 구현으로 교체한다.
     *
     * @return 수집된 snapshot 목록 (Phase 1: 빈 목록)
     */
    private List<StockSnapshotEvent> collectSnapshotsFromTopic() {
        // Phase 1: snapshot 발행 훅(T3-01) 미구현 — 빈 목록 반환
        // Phase 3+: KafkaConsumer로 product.events.stock-snapshot 토픽 전체 replay
        return List.of();
    }
}
