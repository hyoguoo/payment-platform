package com.hyoguoo.paymentplatform.product.mock

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

class FakeEventDedupeStoreTest {
    @Test
    @DisplayName("미존재 UUID — 최초 기록 시 true 반환")
    fun recordIfAbsent_NewUuid_ReturnsTrue() {
        val store = FakeEventDedupeStore()
        val now = BASE_TIME
        val expiresAt = BASE_TIME.plusSeconds(3600)

        val result = store.recordIfAbsent("uuid-1", now, expiresAt)

        assertThat(result).isTrue
        assertThat(store.contains("uuid-1")).isTrue
    }

    @Test
    @DisplayName("중복 UUID (TTL 미만료) — 재호출 시 false 반환")
    fun recordIfAbsent_DuplicateUuid_ReturnsFalse() {
        val store = FakeEventDedupeStore()
        val now = BASE_TIME
        val expiresAt = BASE_TIME.plusSeconds(3600)

        store.recordIfAbsent("uuid-1", now, expiresAt)
        val duplicate = store.recordIfAbsent("uuid-1", now, expiresAt)

        assertThat(duplicate).isFalse
    }

    @Test
    @DisplayName("TTL 만료 후 재호출 — 만료 엔트리 덮어쓰기 후 true 반환")
    fun recordIfAbsent_AfterTtlExpiry_ReturnsTrue() {
        val store = FakeEventDedupeStore()

        // 최초 기록: expiresAt = BASE_TIME+1s (곧 만료)
        val shortTtl = BASE_TIME.plusSeconds(1)
        store.recordIfAbsent("uuid-expire", BASE_TIME, shortTtl)

        // now = BASE_TIME+100s → expiresAt(BASE_TIME+1s) < now → 만료 판정
        val futureNow = BASE_TIME.plusSeconds(100)
        val reprocessed = store.recordIfAbsent("uuid-expire", futureNow, BASE_TIME.plusSeconds(200))

        assertThat(reprocessed).isTrue
    }

    @Test
    @DisplayName("복수 UUID — 독립적으로 추적")
    fun recordIfAbsent_MultipleUuids_TrackedIndependently() {
        val store = FakeEventDedupeStore()
        val now = BASE_TIME
        val expiresAt = BASE_TIME.plusSeconds(3600)

        assertThat(store.recordIfAbsent("uuid-A", now, expiresAt)).isTrue
        assertThat(store.recordIfAbsent("uuid-B", now, expiresAt)).isTrue
        assertThat(store.recordIfAbsent("uuid-A", now, expiresAt)).isFalse
        assertThat(store.recordIfAbsent("uuid-B", now, expiresAt)).isFalse
        assertThat(store.size()).isEqualTo(2)
    }

    private companion object {
        val BASE_TIME: Instant = Instant.parse("2026-04-21T00:00:00Z")
    }
}
