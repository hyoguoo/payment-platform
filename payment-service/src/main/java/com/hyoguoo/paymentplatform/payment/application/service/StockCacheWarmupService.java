package com.hyoguoo.paymentplatform.payment.application.service;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.dto.event.StockSnapshotEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 재고 캐시 warmup 오케스트레이션 서비스 — S-3(Reconciler 전제).
 *
 * <p>ApplicationReadyEvent 시 {@link StockSnapshotWarmupService#onApplicationReady()}를 통해 호출된다.
 * snapshot 목록을 받아 StockCachePort에 SET하고, warmupCompleted 플래그를 활성화한다.
 *
 * <p>빈 토픽(snapshot 없음): 캐시를 설정하지 않고 경고 로그를 남긴 후 완료 처리.
 * 결제 차단 연결(isWarmupCompleted() 질의)은 T1-18(Gateway 라우팅) 또는 별도 훅에서 처리한다.
 */
@Slf4j
@Service
public class StockCacheWarmupService {

    private final StockCachePort stockCachePort;
    private final AtomicBoolean warmupCompleted = new AtomicBoolean(false);

    public StockCacheWarmupService(StockCachePort stockCachePort) {
        this.stockCachePort = stockCachePort;
    }

    /**
     * snapshot 목록을 받아 Redis 캐시를 초기화한다.
     *
     * <p>동일 productId가 복수로 존재하면 목록 순서상 마지막(최신) 값으로 덮어쓴다.
     * 빈 목록이면 캐시를 건드리지 않고 경고 로그 후 완료 처리.
     *
     * @param snapshots product.events.stock-snapshot 토픽에서 수집된 snapshot 목록
     */
    public void applySnapshots(List<StockSnapshotEvent> snapshots) {
        if (snapshots.isEmpty()) {
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SKIPPED,
                    () -> "StockCacheWarmup: snapshot 토픽이 비어 있음 — 캐시 초기화 생략, warmup 완료 처리");
            warmupCompleted.set(true);
            return;
        }

        for (StockSnapshotEvent snapshot : snapshots) {
            stockCachePort.set(snapshot.productId(), snapshot.quantity());
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SUCCESS_COMPLETION,
                    () -> "StockCacheWarmup: productId=" + snapshot.productId()
                            + " quantity=" + snapshot.quantity() + " SET 완료");
        }

        warmupCompleted.set(true);
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SUCCESS_COMPLETION,
                () -> "StockCacheWarmup: " + snapshots.size() + "개 항목 Redis 초기화 완료");
    }

    /**
     * 개별 snapshot 이벤트를 처리한다.
     * {@link com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer.StockSnapshotWarmupConsumer}가
     * Kafka 메시지를 수신할 때 호출한다.
     *
     * @param event 수신한 snapshot 이벤트
     */
    public void handleSnapshot(StockSnapshotEvent event) {
        stockCachePort.set(event.productId(), event.quantity());
        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_RECOVERY_SUCCESS_COMPLETION,
                () -> "StockCacheWarmup: handleSnapshot productId=" + event.productId()
                        + " quantity=" + event.quantity());
    }

    /**
     * warmup 완료 여부 질의.
     * T1-18(Gateway 라우팅) 또는 결제 차단 로직에서 호출한다.
     *
     * @return warmup 완료 시 true
     */
    public boolean isWarmupCompleted() {
        return warmupCompleted.get();
    }
}
