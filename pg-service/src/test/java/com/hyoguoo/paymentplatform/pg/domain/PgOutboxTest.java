package com.hyoguoo.paymentplatform.pg.domain;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static java.time.temporal.ChronoUnit.SECONDS;

/**
 * PgOutbox 도메인 factory 메서드 단위 테스트.
 *
 * <p>{@code create} / {@code createWithAvailableAt} 의
 * 시그니처 / 필드 매핑 / isPending 상태를 검증한다.
 */
@DisplayName("PgOutbox — factory 메서드 + builder 회귀 방어")
class PgOutboxTest {

    private static final String TOPIC = "payment.commands.confirm";
    private static final String KEY = "order-cba9-001";
    private static final String PAYLOAD = "{\"orderId\":\"order-cba9-001\"}";
    private static final String HEADERS_JSON = "{\"attempt\":1}";

    // =========================================================================
    // create — Long id 없는 4-arg 시그니처
    // =========================================================================

    @Test
    @DisplayName("create — Instant now 포함 5-arg 시그니처 → isPending=true, attempt=0, availableAt == now")
    void create_withoutId_availableAtIsNow() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        PgOutbox outbox = PgOutbox.create(TOPIC, KEY, PAYLOAD, HEADERS_JSON, now);

        assertThat(outbox.isPending()).isTrue();
        assertThat(outbox.getAttempt()).isZero();
        assertThat(outbox.getTopic()).isEqualTo(TOPIC);
        assertThat(outbox.getKey()).isEqualTo(KEY);
        assertThat(outbox.getPayload()).isEqualTo(PAYLOAD);
        assertThat(outbox.getHeadersJson()).isEqualTo(HEADERS_JSON);
        assertThat(outbox.getId()).isNull();
        assertThat(outbox.getAvailableAt()).isEqualTo(now);
        assertThat(outbox.getCreatedAt()).isEqualTo(now);
    }

    // =========================================================================
    // createWithAvailableAt — Long id 없는 6-arg 시그니처
    // =========================================================================

    @Test
    @DisplayName("createWithAvailableAt — Instant now + availableAt 6-arg 시그니처 → availableAt == futureAt, isPending=true")
    void createWithAvailableAt_delayedAvailableAt() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        Instant futureAt = now.plusSeconds(30);
        PgOutbox outbox = PgOutbox.createWithAvailableAt(TOPIC, KEY, PAYLOAD, HEADERS_JSON, futureAt, now);

        assertThat(outbox.isPending()).isTrue();
        assertThat(outbox.getAttempt()).isZero();
        assertThat(outbox.getAvailableAt()).isEqualTo(futureAt);
        assertThat(outbox.getId()).isNull();
    }

    // =========================================================================
    // of — 9-arg 풀 생성 (id 유지)
    // =========================================================================

    @Test
    @DisplayName("of — 9-arg 풀 생성 → getId() == 99L 및 모든 필드 정확 매핑")
    void of_fullArgs_constructsCorrectly() {
        Instant availableAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant processedAt = Instant.parse("2026-01-01T00:01:00Z");
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");

        PgOutbox outbox = PgOutbox.of(99L, TOPIC, KEY, PAYLOAD, HEADERS_JSON,
                availableAt, processedAt, 2, createdAt);

        assertThat(outbox.getId()).isEqualTo(99L);
        assertThat(outbox.getTopic()).isEqualTo(TOPIC);
        assertThat(outbox.getKey()).isEqualTo(KEY);
        assertThat(outbox.getPayload()).isEqualTo(PAYLOAD);
        assertThat(outbox.getHeadersJson()).isEqualTo(HEADERS_JSON);
        assertThat(outbox.getAvailableAt()).isEqualTo(availableAt);
        assertThat(outbox.getProcessedAt()).isEqualTo(processedAt);
        assertThat(outbox.getAttempt()).isEqualTo(2);
        assertThat(outbox.getCreatedAt()).isEqualTo(createdAt);
    }
}
