package com.hyoguoo.paymentplatform.product.mock;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FakeEventDedupeStoreTest {

    private static final Instant BASE_TIME = Instant.parse("2026-04-21T00:00:00Z");

    @Test
    @DisplayName("미존재 UUID — 최초 기록 시 true 반환")
    void recordIfAbsent_NewUuid_ReturnsTrue() {
        FakeEventDedupeStore store = new FakeEventDedupeStore();
        Instant now = BASE_TIME;
        Instant expiresAt = BASE_TIME.plusSeconds(3600);

        boolean result = store.recordIfAbsent("uuid-1", now, expiresAt);

        assertThat(result).isTrue();
        assertThat(store.contains("uuid-1")).isTrue();
    }

    @Test
    @DisplayName("중복 UUID (TTL 미만료) — 재호출 시 false 반환")
    void recordIfAbsent_DuplicateUuid_ReturnsFalse() {
        FakeEventDedupeStore store = new FakeEventDedupeStore();
        Instant now = BASE_TIME;
        Instant expiresAt = BASE_TIME.plusSeconds(3600);

        store.recordIfAbsent("uuid-1", now, expiresAt);
        boolean duplicate = store.recordIfAbsent("uuid-1", now, expiresAt);

        assertThat(duplicate).isFalse();
    }

    @Test
    @DisplayName("TTL 만료 후 재호출 — 만료 엔트리 덮어쓰기 후 true 반환")
    void recordIfAbsent_AfterTtlExpiry_ReturnsTrue() {
        FakeEventDedupeStore store = new FakeEventDedupeStore();

        // 최초 기록: expiresAt = BASE_TIME+1s (곧 만료)
        Instant shortTtl = BASE_TIME.plusSeconds(1);
        store.recordIfAbsent("uuid-expire", BASE_TIME, shortTtl);

        // now = BASE_TIME+100s → expiresAt(BASE_TIME+1s) < now → 만료 판정
        Instant futureNow = BASE_TIME.plusSeconds(100);
        boolean reprocessed = store.recordIfAbsent("uuid-expire", futureNow, BASE_TIME.plusSeconds(200));

        assertThat(reprocessed).isTrue();
    }

    @Test
    @DisplayName("복수 UUID — 독립적으로 추적")
    void recordIfAbsent_MultipleUuids_TrackedIndependently() {
        FakeEventDedupeStore store = new FakeEventDedupeStore();
        Instant now = BASE_TIME;
        Instant expiresAt = BASE_TIME.plusSeconds(3600);

        assertThat(store.recordIfAbsent("uuid-A", now, expiresAt)).isTrue();
        assertThat(store.recordIfAbsent("uuid-B", now, expiresAt)).isTrue();
        assertThat(store.recordIfAbsent("uuid-A", now, expiresAt)).isFalse();
        assertThat(store.recordIfAbsent("uuid-B", now, expiresAt)).isFalse();
        assertThat(store.size()).isEqualTo(2);
    }
}
