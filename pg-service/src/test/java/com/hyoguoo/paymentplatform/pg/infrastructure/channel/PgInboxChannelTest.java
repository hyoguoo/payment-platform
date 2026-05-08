package com.hyoguoo.paymentplatform.pg.infrastructure.channel;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PgInboxChannel 단위 테스트.
 *
 * <p>PgOutboxChannel 1:1 거울 구조 검증.
 * LinkedBlockingQueue cap=1024, offerNow(non-blocking) / take(blocking) / size / isNearFull.
 */
@DisplayName("PgInboxChannel")
class PgInboxChannelTest {

    private static final int TEST_CAPACITY = 5;

    private PgInboxChannel channel;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        channel = new PgInboxChannel(TEST_CAPACITY, meterRegistry);
        channel.registerMetrics();
    }

    @Test
    @DisplayName("offerNow_belowCapacity_returnsTrue — 빈 채널에 offerNow 하면 true 반환")
    void offerNow_belowCapacity_returnsTrue() {
        boolean result = channel.offerNow(1L);

        assertThat(result).isTrue();
        assertThat(channel.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("offerNow_atCapacity_returnsFalse — 채널이 가득 찼을 때 offerNow 는 false 반환 (블로킹 없음)")
    void offerNow_atCapacity_returnsFalse() {
        // capacity(5) 개 채우기
        for (long i = 0; i < TEST_CAPACITY; i++) {
            boolean offered = channel.offerNow(i);
            assertThat(offered).isTrue();
        }

        // 하나 더 offer — 큐 full
        boolean overflow = channel.offerNow(99L);
        assertThat(overflow).isFalse();
    }

    @Test
    @DisplayName("take_afterOffer_returnsCorrectInboxId — offerNow(123L) 후 take 하면 inboxId == 123L")
    void take_afterOffer_returnsCorrectInboxId() throws InterruptedException {
        channel.offerNow(123L);

        InboxJob job = channel.take();

        assertThat(job.inboxId()).isEqualTo(123L);
        assertThat(job.otelContext()).isNotNull();
        assertThat(job.snapshot()).isNotNull();
    }

    @Test
    @DisplayName("take_returnsJobInFifoOrder — 적재 순서대로 take 반환")
    void take_returnsJobInFifoOrder() throws InterruptedException {
        channel.offerNow(10L);
        channel.offerNow(20L);
        channel.offerNow(30L);

        assertThat(channel.take().inboxId()).isEqualTo(10L);
        assertThat(channel.take().inboxId()).isEqualTo(20L);
        assertThat(channel.take().inboxId()).isEqualTo(30L);
    }

    @Test
    @DisplayName("size_returnsCurrentQueueDepth — size 가 현재 큐 깊이를 정확히 반환")
    void size_returnsCurrentQueueDepth() {
        assertThat(channel.size()).isEqualTo(0);

        channel.offerNow(1L);
        channel.offerNow(2L);

        assertThat(channel.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("isNearFull_above80Percent_returnsTrue — capacity=5, 4개 적재 시 isNearFull == true")
    void isNearFull_above80Percent_returnsTrue() {
        // 0개: remainingCapacity=5, 5*5=25 < 5 → false
        assertThat(channel.isNearFull()).isFalse();

        // 3개: remainingCapacity=2, 2*5=10 < 5 → false (60% 소진)
        channel.offerNow(1L);
        channel.offerNow(2L);
        channel.offerNow(3L);
        assertThat(channel.isNearFull()).isFalse();

        // 4개: remainingCapacity=1, 1*5=5 < 5 → false (경계값: 정확히 80%)
        channel.offerNow(4L);
        assertThat(channel.isNearFull()).isFalse();

        // 5개(full): remainingCapacity=0, 0*5=0 < 5 → true
        channel.offerNow(5L);
        assertThat(channel.isNearFull()).isTrue();
    }

    @Test
    @DisplayName("metricsRegistered_queueSizeAndRemainingCapacityExposed — pg_inbox_channel_queue_size / remaining_capacity Gauge 등록 검증")
    void metricsRegistered_queueSizeAndRemainingCapacityExposed() {
        channel.offerNow(1L);
        channel.offerNow(2L);

        Gauge sizeGauge = meterRegistry.find("pg_inbox_channel_queue_size").gauge();
        assertThat(sizeGauge).isNotNull();
        assertThat(sizeGauge.value()).isEqualTo(2.0);

        Gauge remainingGauge = meterRegistry.find("pg_inbox_channel_remaining_capacity").gauge();
        assertThat(remainingGauge).isNotNull();
        assertThat(remainingGauge.value()).isEqualTo(TEST_CAPACITY - 2.0);
    }
}
