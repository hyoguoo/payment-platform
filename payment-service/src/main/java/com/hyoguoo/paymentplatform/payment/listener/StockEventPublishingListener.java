package com.hyoguoo.paymentplatform.payment.listener;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.dto.StockRestoreEventPayload;
import com.hyoguoo.paymentplatform.payment.application.event.StockCommitRequestedEvent;
import com.hyoguoo.paymentplatform.payment.application.event.StockRestoreRequestedEvent;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCommitEventPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRestoreEventPublisherPort;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * TX commit 이후 stock Kafka 발행을 담당하는 리스너.
 * ADR-04: TX 내부는 ApplicationEvent 발행만 — 실제 Kafka publish는 AFTER_COMMIT에서 실행.
 *
 * <p>DB TX 블로킹 방지 원칙:
 * <ul>
 *   <li>Kafka broker 지연이 DB TX 홀딩 시간에 영향을 주지 않는다.</li>
 *   <li>commit 성공 후 Kafka 발행 실패 시 TX는 이미 완료 — 복구 정책은 별도 태스크(T-E/F).</li>
 * </ul>
 *
 * <p>실패 처리: LogFmt.error + 예외 삼킴 — TX는 이미 commit이므로 rollback 불가.
 * 추후 DLQ 또는 재시도 정책 도입 시 이 리스너에서 확장.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockEventPublishingListener {

    private final StockCommitEventPublisherPort stockCommitEventPublisherPort;
    private final StockRestoreEventPublisherPort stockRestoreEventPublisherPort;

    /**
     * AFTER_COMMIT: stock.events.commit 실제 Kafka 발행.
     * TX commit 성공 후에만 실행 — 발행 실패는 TX 영향 없음.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStockCommitRequested(StockCommitRequestedEvent event) {
        try {
            stockCommitEventPublisherPort.publish(
                    event.productId(),
                    event.quantity(),
                    event.idempotencyKey()
            );
            LogFmt.debug(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_SUCCESS,
                    () -> "stockCommit orderId=" + event.orderId()
                            + " productId=" + event.productId()
                            + " qty=" + event.quantity());
        } catch (RuntimeException e) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_FAIL,
                    () -> "stockCommit 발행 실패 — orderId=" + event.orderId()
                            + " productId=" + event.productId()
                            + " cause=" + e.getMessage()
                            + " action=SWALLOW(TX already committed)");
        }
    }

    /**
     * AFTER_COMMIT: stock.events.restore 실제 Kafka 발행.
     * TX commit 성공 후에만 실행 — 발행 실패는 TX 영향 없음.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStockRestoreRequested(StockRestoreRequestedEvent event) {
        StockRestoreEventPayload payload = buildRestorePayload(event);
        try {
            stockRestoreEventPublisherPort.publishPayload(payload);
            LogFmt.debug(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_SUCCESS,
                    () -> "stockRestore orderId=" + event.orderId()
                            + " productId=" + event.productId()
                            + " qty=" + event.quantity());
        } catch (RuntimeException e) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_FAIL,
                    () -> "stockRestore 발행 실패 — orderId=" + event.orderId()
                            + " productId=" + event.productId()
                            + " cause=" + e.getMessage()
                            + " action=SWALLOW(TX already committed)");
        }
    }

    private static StockRestoreEventPayload buildRestorePayload(StockRestoreRequestedEvent event) {
        UUID eventUUID = UUID.fromString(event.eventUUID());
        return new StockRestoreEventPayload(
                eventUUID,
                event.orderId(),
                event.productId(),
                event.quantity()
        );
    }
}
