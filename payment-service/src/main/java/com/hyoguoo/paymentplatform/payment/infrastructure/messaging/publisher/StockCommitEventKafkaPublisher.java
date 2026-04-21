package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.publisher;

import com.hyoguoo.paymentplatform.payment.application.port.out.MessagePublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCommitEventPublisherPort;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.event.StockCommittedEvent;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * StockCommitEventPublisherPort 구현체 — Kafka 직접 발행 어댑터.
 *
 * <p>멱등성 정책: 호출자(Coordinator) 책임.
 * 이 publisher는 단순 Kafka send 어댑터이며 내부에서 중복 발행을 차단하지 않는다.
 * 호출자는 결제 DONE 상태 전이 시 정확히 1회만 publish()를 호출함으로써 멱등성을 보장한다.
 *
 * <p>파티션 키: productId.toString() — 동일 상품 이벤트를 동일 파티션에 라우팅해 순서를 보장한다.
 * (ADR-12 파티션 키 선택 지침)
 *
 * <p>MessagePublisherPort를 경유한다 (KafkaTemplate 직접 호출 금지 — Round 2 C-1/C-3).
 * 런타임 Kafka 구현체(KafkaMessagePublisher)는 T1-11a에서 신설 예정이며,
 * 그 전까지는 테스트에서 FakeMessagePublisher를 주입해 검증한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(MessagePublisherPort.class)
public class StockCommitEventKafkaPublisher implements StockCommitEventPublisherPort {

    private final MessagePublisherPort messagePublisherPort;

    @Override
    public void publish(Long productId, int qty, String idempotencyKey) {
        StockCommittedEvent payload = new StockCommittedEvent(
                productId,
                qty,
                idempotencyKey,
                Instant.now()
        );
        messagePublisherPort.send(
                PaymentTopics.EVENTS_STOCK_COMMITTED,
                String.valueOf(productId),
                payload
        );
    }
}
