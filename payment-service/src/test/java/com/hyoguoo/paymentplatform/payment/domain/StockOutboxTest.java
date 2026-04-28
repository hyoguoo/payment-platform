package com.hyoguoo.paymentplatform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StockOutbox 도메인")
class StockOutboxTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 12, 0);

    @Test
    @DisplayName("create() 는 미처리 상태 + attempt 0 + availableAt=now 로 신규 row 를 만든다")
    void create_initializesPendingRow() {
        StockOutbox outbox = StockOutbox.create("topic", "key", "{}", NOW);

        assertThat(outbox.getId()).isNull();
        assertThat(outbox.getTopic()).isEqualTo("topic");
        assertThat(outbox.getKey()).isEqualTo("key");
        assertThat(outbox.getPayload()).isEqualTo("{}");
        assertThat(outbox.getHeadersJson()).isNull();
        assertThat(outbox.getAvailableAt()).isEqualTo(NOW);
        assertThat(outbox.getProcessedAt()).isNull();
        assertThat(outbox.getAttempt()).isZero();
        assertThat(outbox.getCreatedAt()).isEqualTo(NOW);
        assertThat(outbox.isPending()).isTrue();
    }

    @Test
    @DisplayName("of() 는 DB 조회 결과를 그대로 복원하고 processedAt 이 있으면 isPending=false 다")
    void of_restoresAllFields() {
        LocalDateTime processedAt = NOW.plusMinutes(1);

        StockOutbox outbox = StockOutbox.of(
                42L,
                "t",
                "k",
                "{}",
                "{\"h\":1}",
                NOW,
                processedAt,
                3,
                NOW.minusHours(1)
        );

        assertThat(outbox.getId()).isEqualTo(42L);
        assertThat(outbox.getTopic()).isEqualTo("t");
        assertThat(outbox.getKey()).isEqualTo("k");
        assertThat(outbox.getPayload()).isEqualTo("{}");
        assertThat(outbox.getHeadersJson()).isEqualTo("{\"h\":1}");
        assertThat(outbox.getAvailableAt()).isEqualTo(NOW);
        assertThat(outbox.getProcessedAt()).isEqualTo(processedAt);
        assertThat(outbox.getAttempt()).isEqualTo(3);
        assertThat(outbox.getCreatedAt()).isEqualTo(NOW.minusHours(1));
        assertThat(outbox.isPending()).isFalse();
    }

    @Test
    @DisplayName("markProcessed() 는 processedAt 을 설정해 isPending 을 false 로 뒤집는다")
    void markProcessed_setsProcessedAtAndFlipsPending() {
        StockOutbox outbox = StockOutbox.create("topic", "key", "{}", NOW);
        assertThat(outbox.isPending()).isTrue();

        LocalDateTime processedAt = NOW.plusMinutes(5);
        outbox.markProcessed(processedAt);

        assertThat(outbox.getProcessedAt()).isEqualTo(processedAt);
        assertThat(outbox.isPending()).isFalse();
    }

    @Test
    @DisplayName("incrementAttempt() 는 attempt 를 1 씩 누적 증가시킨다")
    void incrementAttempt_increasesByOne() {
        StockOutbox outbox = StockOutbox.create("topic", "key", "{}", NOW);
        assertThat(outbox.getAttempt()).isZero();

        outbox.incrementAttempt();
        assertThat(outbox.getAttempt()).isEqualTo(1);

        outbox.incrementAttempt();
        assertThat(outbox.getAttempt()).isEqualTo(2);
    }
}
