package com.hyoguoo.paymentplatform.core.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PaymentConfirmChannel 테스트")
class PaymentConfirmChannelTest {

    private static final int CAPACITY = 10;

    private PaymentConfirmChannel channel;

    @BeforeEach
    void setUp() {
        channel = new PaymentConfirmChannel(CAPACITY, new SimpleMeterRegistry());
        channel.registerMetrics();
    }

    @Test
    @DisplayName("offer - 큐에 여유가 있으면 true를 반환한다")
    void offer_큐_여유_있음_true() {
        boolean result = channel.offer("order-1");

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("offer - 큐가 가득 차면 false를 반환한다")
    void offer_큐_가득_참_false() {
        for (int i = 0; i < CAPACITY; i++) {
            channel.offer("order-" + i);
        }

        boolean result = channel.offer("order-overflow");

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("take - offer한 orderId를 반환한다")
    void take_offer한_orderId_반환() throws InterruptedException {
        channel.offer("order-1");

        String taken = channel.take();

        assertThat(taken).isEqualTo("order-1");
    }

    @Test
    @DisplayName("isNearFull - 잔여 용량이 임계값 초과이면 false를 반환한다")
    void isNearFull_잔여_용량_충분_false() {
        assertThat(channel.isNearFull()).isFalse();
    }

    @Test
    @DisplayName("isNearFull - 잔여 용량이 10% 이하이면 true를 반환한다")
    void isNearFull_잔여_용량_임계값_이하_true() {
        // capacity=10, threshold=10% → remainingCapacity <= 1 이면 nearFull
        for (int i = 0; i < CAPACITY - 1; i++) {
            channel.offer("order-" + i);
        }

        assertThat(channel.isNearFull()).isTrue();
    }
}
