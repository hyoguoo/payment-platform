package com.hyoguoo.paymentplatform.payment.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FakeEventDedupeStore two-phase lease 계약 검증 —
 * markWithLease / extendLease / boolean remove 시나리오를 다룬다.
 */
@DisplayName("FakeEventDedupeStore — two-phase lease 계약")
class FakeEventDedupeStoreLeaseTest {

    private static final String UUID = "evt-lease-001";
    private static final Duration SHORT_TTL = Duration.ofMinutes(5);
    private static final Duration LONG_TTL = Duration.ofDays(8);

    private MutableClock clock;
    private FakeEventDedupeStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-04-24T10:00:00Z"));
        store = new FakeEventDedupeStore(clock);
    }

    // -----------------------------------------------------------------------
    // markWithLease_setsShortTtl
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("markWithLease — 최초 호출 시 true 반환 (처리 권한 획득)")
    void markWithLease_firstCall_returnsTrue() {
        boolean result = store.markWithLease(UUID, SHORT_TTL);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("markWithLease — 동일 UUID 두 번째 호출 시 false 반환 (이미 처리 중)")
    void markWithLease_secondCall_returnsFalse() {
        store.markWithLease(UUID, SHORT_TTL);
        boolean result = store.markWithLease(UUID, SHORT_TTL);
        assertThat(result).isFalse();
    }

    // -----------------------------------------------------------------------
    // extendLease_afterMark_extendsToLongTtl
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("extendLease — markWithLease 후 호출 시 true 반환 (TTL 연장)")
    void extendLease_afterMark_returnsTrue() {
        store.markWithLease(UUID, SHORT_TTL);
        boolean result = store.extendLease(UUID, LONG_TTL);
        assertThat(result).isTrue();
    }

    // -----------------------------------------------------------------------
    // extendLease_whenNotMarked_returnsFalse
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("extendLease — mark 없이 호출 시 false 반환 (키 없음)")
    void extendLease_whenNotMarked_returnsFalse() {
        boolean result = store.extendLease(UUID, LONG_TTL);
        assertThat(result).isFalse();
    }

    // -----------------------------------------------------------------------
    // markWithLease_whenLeaseExpired_shouldRemark
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("markWithLease — shortTtl 만료 후 재호출 시 true 반환 (재처리 가능)")
    void markWithLease_whenLeaseExpired_shouldRemark() {
        // given — short TTL로 마크
        store.markWithLease(UUID, SHORT_TTL);
        assertThat(store.markWithLease(UUID, SHORT_TTL)).isFalse(); // 유효 중

        // when — short TTL 경과
        clock.advance(SHORT_TTL.plusSeconds(1));

        // then — 재마크 가능
        assertThat(store.markWithLease(UUID, SHORT_TTL)).isTrue();
    }

    @Test
    @DisplayName("extendLease 후 longTtl 만료 전에는 재마크 불가")
    void markWithLease_afterExtend_withinLongTtl_returnsFalse() {
        // given
        store.markWithLease(UUID, SHORT_TTL);
        store.extendLease(UUID, LONG_TTL);

        // when — short TTL 경과 (하지만 long TTL 이내)
        clock.advance(SHORT_TTL.plusSeconds(1));

        // then — long TTL로 연장됐으므로 재마크 불가
        assertThat(store.markWithLease(UUID, SHORT_TTL)).isFalse();
    }

    // -----------------------------------------------------------------------
    // boolean remove
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("remove — 존재하는 키 삭제 시 true 반환")
    void remove_existingKey_returnsTrue() {
        store.markWithLease(UUID, SHORT_TTL);
        boolean result = store.remove(UUID);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("remove — 존재하지 않는 키 삭제 시 false 반환")
    void remove_nonExistingKey_returnsFalse() {
        boolean result = store.remove("non-existing-uuid");
        assertThat(result).isFalse();
    }

    // -----------------------------------------------------------------------
    // 내부 Clock 헬퍼
    // -----------------------------------------------------------------------

    static class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant initial) {
            this.now = initial;
        }

        void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
