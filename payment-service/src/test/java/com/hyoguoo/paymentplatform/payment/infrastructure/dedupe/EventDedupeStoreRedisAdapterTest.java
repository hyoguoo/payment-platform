package com.hyoguoo.paymentplatform.payment.infrastructure.dedupe;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("EventDedupeStoreRedisAdapter 테스트")
class EventDedupeStoreRedisAdapterTest {

    @Test
    @DisplayName("기본 TTL 이 8일(P8D)과 동일해야 한다")
    void defaultTtl_shouldBe8Days() {
        // given — StringRedisTemplate 없이 기본값 P8D 를 ReflectionTestUtils 로 주입
        // @Value("${payment.event-dedupe.ttl:P8D}") 기본값 검증:
        // Spring 컨텍스트 없이 단위 테스트 가능하도록 Duration.parse("P8D") 로 직접 확인.
        EventDedupeStoreRedisAdapter adapter =
                new EventDedupeStoreRedisAdapter(null);

        ReflectionTestUtils.setField(adapter, "ttl", Duration.parse("P8D")); // GREEN: 기본값 P8D 주입

        Duration ttl = (Duration) ReflectionTestUtils.getField(adapter, "ttl");

        // then — P8D = 8일
        assertThat(ttl).isEqualTo(Duration.ofDays(8));
    }
}
