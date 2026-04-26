package com.hyoguoo.paymentplatform.payment.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.event.StockOutboxReadyEvent;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockOutboxRepository;
import com.hyoguoo.paymentplatform.payment.application.util.StockEventUuidDeriver;
import com.hyoguoo.paymentplatform.payment.application.util.StockOutboxFactory;
import com.hyoguoo.paymentplatform.payment.domain.StockOutbox;
import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.application.dto.event.StockRestoreEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * FAILED 결제에 대한 재고 복원 보상.
 *
 * <p>stock.events.restore 보상은 transactional outbox 패턴으로 처리한다 — TX 내부에서 stock_outbox 를
 * INSERT 하고 {@link StockOutboxReadyEvent} 만 발행한다. 실 Kafka publish 는 AFTER_COMMIT 비동기 리스너 책임.
 *
 * <p>UUID 결정론: {@code "stock-restore:{orderId}:{productId}"} 로 UUID v3 를 생성한다 — 동일
 * orderId+productId 재호출 시 같은 UUID 가 나와 product-service dedupe 와 결합해 중복 복원을 차단한다.
 */
@Service
@RequiredArgsConstructor
public class FailureCompensationService {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final StockOutboxRepository stockOutboxRepository;
    private final ObjectMapper objectMapper;
    private final LocalDateTimeProvider localDateTimeProvider;

    /**
     * 여러 상품을 한 번에 복원할 때의 진입점. 각 productId 별로 단일 compensate 를 그대로 호출한다.
     */
    public void compensate(String orderId, List<Long> productIds, int qty) {
        for (Long productId : productIds) {
            compensate(orderId, productId, qty);
        }
    }

    /**
     * 단일 상품 재고 복원 outbox row INSERT + StockOutboxReadyEvent 발행.
     * eventUuid 는 (orderId, productId, "stock-restore") 로 결정되며, 같은 조합이 다시 들어와도 같은 UUID 가 나온다.
     */
    public void compensate(String orderId, Long productId, int qty) {
        UUID eventUUID = UUID.fromString(StockEventUuidDeriver.derive(orderId, productId, "stock-restore"));
        Instant occurredAt = localDateTimeProvider.nowInstant();
        StockRestoreEvent event = new StockRestoreEvent(eventUUID, orderId, productId, qty, occurredAt);
        String payloadJson = StockOutboxFactory.serialize(event, objectMapper);

        LocalDateTime now = localDateTimeProvider.now();
        StockOutbox outbox = StockOutbox.create(
                PaymentTopics.EVENTS_STOCK_RESTORE,
                String.valueOf(productId),
                payloadJson,
                now
        );
        StockOutbox saved = stockOutboxRepository.save(outbox);
        applicationEventPublisher.publishEvent(new StockOutboxReadyEvent(saved.getId()));
    }

}
