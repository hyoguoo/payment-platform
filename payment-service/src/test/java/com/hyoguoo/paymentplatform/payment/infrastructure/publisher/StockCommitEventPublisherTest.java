package com.hyoguoo.paymentplatform.payment.infrastructure.publisher;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.event.StockCommittedEvent;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.publisher.StockCommitEventKafkaPublisher;
import com.hyoguoo.paymentplatform.payment.mock.FakeMessagePublisher;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * StockCommitEventKafkaPublisher 단위 테스트.
 *
 * <p>멱등성 정책: (c) 호출자 책임.
 * 이 publisher는 단순 Kafka send 어댑터이므로 내부에서 중복 발행을 차단하지 않는다.
 * 호출자(Coordinator)가 결제 DONE 상태 전이 시 정확히 1회만 publish()를 호출함으로써
 * 멱등성을 보장한다. 동일 idempotencyKey로 두 번 호출하면 두 번 모두 발행된다.
 */
@DisplayName("StockCommitEventKafkaPublisher 테스트")
class StockCommitEventPublisherTest {

    private FakeMessagePublisher fakeMessagePublisher;
    private StockCommitEventKafkaPublisher publisher;

    @BeforeEach
    void setUp() {
        fakeMessagePublisher = new FakeMessagePublisher();
        publisher = new StockCommitEventKafkaPublisher(fakeMessagePublisher);
    }

    @Test
    @DisplayName("publish() 호출 시 payment.events.stock-committed 토픽으로 1회 발행한다")
    void publish_WhenPaymentConfirmed_ShouldEmitStockCommittedEvent() {
        // given
        Long productId = 1L;
        int qty = 3;
        String idempotencyKey = "order-abc-123";

        // when
        publisher.publish(productId, qty, idempotencyKey);

        // then
        List<FakeMessagePublisher.SentMessage> messages =
                fakeMessagePublisher.findByTopic(PaymentTopics.EVENTS_STOCK_COMMITTED);
        assertThat(messages).hasSize(1);
    }

    @Test
    @DisplayName("publish() 호출 시 payload에 productId, qty, idempotencyKey 필드가 포함된다")
    void publish_ShouldIncludeProductIdQtyAndPaymentEventId() {
        // given
        Long productId = 42L;
        int qty = 5;
        String idempotencyKey = "order-xyz-999";

        // when
        publisher.publish(productId, qty, idempotencyKey);

        // then
        FakeMessagePublisher.SentMessage message =
                fakeMessagePublisher.findByTopic(PaymentTopics.EVENTS_STOCK_COMMITTED)
                        .get(0);
        assertThat(message.key()).isEqualTo(String.valueOf(productId));

        StockCommittedEvent event = (StockCommittedEvent) message.payload();
        assertThat(event.productId()).isEqualTo(productId);
        assertThat(event.qty()).isEqualTo(qty);
        assertThat(event.idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    @DisplayName("동일 idempotencyKey로 두 번 호출 시 두 번 모두 발행한다 — 멱등성은 호출자(Coordinator) 책임")
    void publish_IsIdempotent_WhenCalledTwice_CallerResponsibility() {
        // given
        Long productId = 7L;
        int qty = 2;
        String idempotencyKey = "order-dup-001";

        // when — 동일 호출 2회 (실제 운영에서는 Coordinator가 DONE 전이 후 1회만 호출)
        publisher.publish(productId, qty, idempotencyKey);
        publisher.publish(productId, qty, idempotencyKey);

        // then — publisher 자체는 중복 차단 없이 2회 모두 발행 (멱등성은 호출자 책임)
        List<FakeMessagePublisher.SentMessage> messages =
                fakeMessagePublisher.findByTopic(PaymentTopics.EVENTS_STOCK_COMMITTED);
        assertThat(messages).hasSize(2);
    }
}
