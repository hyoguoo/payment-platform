package com.hyoguoo.paymentplatform.product.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FakeEventDedupeStoreTest {

    private static final Instant BASE_TIME = Instant.parse("2026-04-21T00:00:00Z");

    @Test
    @DisplayName("미존재 UUID — 최초 기록 시 true 반환")
    void recordIfAbsent_NewUuid_ReturnsTrue() {
        FakeEventDedupeStore store = new FakeEventDedupeStore(
                Clock.fixed(BASE_TIME, ZoneOffset.UTC)
        );
        Instant expiresAt = BASE_TIME.plusSeconds(3600);

        boolean result = store.recordIfAbsent("uuid-1", expiresAt);

        assertThat(result).isTrue();
        assertThat(store.contains("uuid-1")).isTrue();
    }

    @Test
    @DisplayName("중복 UUID (TTL 미만료) — 재호출 시 false 반환")
    void recordIfAbsent_DuplicateUuid_ReturnsFalse() {
        FakeEventDedupeStore store = new FakeEventDedupeStore(
                Clock.fixed(BASE_TIME, ZoneOffset.UTC)
        );
        Instant expiresAt = BASE_TIME.plusSeconds(3600);

        store.recordIfAbsent("uuid-1", expiresAt);
        boolean duplicate = store.recordIfAbsent("uuid-1", expiresAt);

        assertThat(duplicate).isFalse();
    }

    @Test
    @DisplayName("TTL 만료 후 재호출 — 만료 엔트리 덮어쓰기 후 true 반환")
    void recordIfAbsent_AfterTtlExpiry_ReturnsTrue() {
        // 최초 기록: clock=BASE_TIME, expiresAt=BASE_TIME+1s (곧 만료)
        FakeEventDedupeStore store = new FakeEventDedupeStore(
                Clock.fixed(BASE_TIME, ZoneOffset.UTC)
        );
        Instant shortTtl = BASE_TIME.plusSeconds(1);
        store.recordIfAbsent("uuid-1", shortTtl);

        // 시간 경과 후(BASE_TIME+2s) 동일 UUID 재호출 — 기존 엔트리 expiresAt(BASE_TIME+1s) < now(BASE_TIME+2s)
        FakeEventDedupeStore storeAfterExpiry = new FakeEventDedupeStore(
                Clock.fixed(BASE_TIME.plusSeconds(2), ZoneOffset.UTC)
        );
        // 만료 시뮬레이션을 위해 동일 store에 Clock을 주입해야 하므로
        // 같은 인스턴스에 clock 교체가 불가 — 별도 store로 검증 (동일 in-memory 상태 불필요)
        // 대신 TTL=과거로 기록 후 현재 시각이 미래인 store로 재확인
        FakeEventDedupeStore storeWithFutureClock = new FakeEventDedupeStore(
                Clock.fixed(BASE_TIME.plusSeconds(100), ZoneOffset.UTC)
        );
        // expiresAt=BASE_TIME+1s(과거), now=BASE_TIME+100s → 만료
        storeWithFutureClock.recordIfAbsent("uuid-expire", BASE_TIME.plusSeconds(1));
        // 이 시점에서 store 안의 expiresAt는 이미 past → 재호출 true 기대
        boolean reprocessed = storeWithFutureClock.recordIfAbsent("uuid-expire", BASE_TIME.plusSeconds(200));

        assertThat(reprocessed).isTrue();
    }

    @Test
    @DisplayName("복수 UUID — 독립적으로 추적")
    void recordIfAbsent_MultipleUuids_TrackedIndependently() {
        FakeEventDedupeStore store = new FakeEventDedupeStore(
                Clock.fixed(BASE_TIME, ZoneOffset.UTC)
        );
        Instant expiresAt = BASE_TIME.plusSeconds(3600);

        assertThat(store.recordIfAbsent("uuid-A", expiresAt)).isTrue();
        assertThat(store.recordIfAbsent("uuid-B", expiresAt)).isTrue();
        assertThat(store.recordIfAbsent("uuid-A", expiresAt)).isFalse();
        assertThat(store.recordIfAbsent("uuid-B", expiresAt)).isFalse();
        assertThat(store.size()).isEqualTo(2);
    }
}
