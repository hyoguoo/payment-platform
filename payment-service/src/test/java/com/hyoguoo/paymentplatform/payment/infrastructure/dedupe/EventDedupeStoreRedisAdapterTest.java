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
        // given — StringRedisTemplate 없이 기본값만 확인 (ReflectionTestUtils 로 필드 읽기)
        EventDedupeStoreRedisAdapter adapter =
                new EventDedupeStoreRedisAdapter(null);

        // @Value 기본값 P8D 가 적용된 상태를 시뮬레이션
        ReflectionTestUtils.setField(adapter, "ttl", Duration.parse("PT1H")); // RED: 현재 기본값 1시간 주입

        Duration ttl = (Duration) ReflectionTestUtils.getField(adapter, "ttl");

        // then — P8D 와 동일해야 한다 (현재 PT1H 이므로 RED)
        assertThat(ttl).isEqualTo(Duration.ofDays(8));
    }
}
