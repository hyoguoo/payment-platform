package com.hyoguoo.paymentplatform.pg.infrastructure.channel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PgOutboxChannel 단위 스모크 테스트.
 * domain_risk=true: offer/take 정상 동작, capacity full 시 offer=false, isNearFull 임계치.
 *
 * <p>T-J4: LinkedBlockingQueue&lt;Long&gt; → LinkedBlockingQueue&lt;OutboxJob&gt; 변경 반영.
 * take() 반환 타입이 OutboxJob 이므로 outboxId 추출 검증으로 보정.
 */
@DisplayName("PgOutboxChannel")
class PgOutboxChannelTest {

    private static final int TEST_CAPACITY = 10;

    private PgOutboxChannel channel;

    @BeforeEach
    void setUp() {
        channel = new PgOutboxChannel(TEST_CAPACITY, new SimpleMeterRegistry());
        channel.registerMetrics();
    }

    @Test
    @DisplayName("offer — 빈 큐에 offer 하면 true 반환, take 로 꺼낸 outboxId 가 일치한다")
    void offer_take_정상동작() throws InterruptedException {
        boolean offered = channel.offerNow(42L);

        assertThat(offered).isTrue();
        assertThat(channel.size()).isEqualTo(1);

        OutboxJob job = channel.take();
        assertThat(job.outboxId()).isEqualTo(42L);
        assertThat(channel.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("offer — 큐가 capacity 만큼 찼을 때 offer 는 false 를 반환한다 (Polling Worker fallback)")
    void offer_큐_full_시_false_반환() {
        // capacity(10) 개 채우기
        for (long i = 0; i < TEST_CAPACITY; i++) {
            boolean offered = channel.offerNow(i);
            assertThat(offered).isTrue();
        }

        // 하나 더 offer — 큐 full
        boolean overflow = channel.offerNow(99L);
        assertThat(overflow).isFalse();
    }

    @Test
    @DisplayName("isNearFull — 잔여 용량이 20% 초과이면 false, 20% 이하이면 true 반환")
    void isNearFull_임계치_검증() {
        // 0개: remainingCapacity = 10, 10*5=50 < 10 → false
        assertThat(channel.isNearFull()).isFalse();

        // 8개 채움: remainingCapacity = 2, 2*5=10 < 10 → false (경계값: 정확히 80%)
        for (long i = 0; i < 8; i++) {
            channel.offerNow(i);
        }
        assertThat(channel.isNearFull()).isFalse();

        // 9개 채움: remainingCapacity = 1, 1*5=5 < 10 → true (80% 초과 — nearFull)
        channel.offerNow(99L);
        assertThat(channel.isNearFull()).isTrue();
    }

    @Test
    @DisplayName("size — 큐에 있는 항목 수를 정확하게 반환한다")
    void size_정확한_항목수_반환() {
        assertThat(channel.size()).isEqualTo(0);

        channel.offerNow(1L);
        channel.offerNow(2L);
        channel.offerNow(3L);

        assertThat(channel.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("offer — 1024 capacity 설정 시 큐 용량이 올바르게 제한된다")
    void offer_capacity_1024_제한_확인() throws Exception {
        // 실제 1024 용량으로 채널 생성
        PgOutboxChannel largeChannel = new PgOutboxChannel(1024, new SimpleMeterRegistry());
        largeChannel.registerMetrics();

        // 내부 queue 필드에서 capacity 확인 (remainingCapacity 로 간접 검증)
        Field queueField = PgOutboxChannel.class.getDeclaredField("queue");
        queueField.setAccessible(true);
        @SuppressWarnings("unchecked")
        LinkedBlockingQueue<OutboxJob> internalQueue = (LinkedBlockingQueue<OutboxJob>) queueField.get(largeChannel);

        assertThat(internalQueue.remainingCapacity()).isEqualTo(1024);
    }
}
