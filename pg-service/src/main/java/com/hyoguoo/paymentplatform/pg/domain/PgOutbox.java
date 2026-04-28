package com.hyoguoo.paymentplatform.pg.domain;

import java.time.Instant;

/**
 * pg-service outbox 도메인 POJO.
 * JPA 엔티티(PgOutboxEntity) 와 도메인 객체를 분리해 hexagonal 경계를 유지한다.
 *
 * <p>available_at 지연 발행, attempt 재시도 횟수 추적.
 */
public class PgOutbox {

    private final Long id;
    private final String topic;
    private final String key;
    private final String payload;
    private final String headersJson;
    private final Instant availableAt;
    private Instant processedAt;
    private int attempt;
    private final Instant createdAt;

    private PgOutbox(
            Long id,
            String topic,
            String key,
            String payload,
            String headersJson,
            Instant availableAt,
            Instant processedAt,
            int attempt,
            Instant createdAt) {
        this.id = id;
        this.topic = topic;
        this.key = key;
        this.payload = payload;
        this.headersJson = headersJson;
        this.availableAt = availableAt;
        this.processedAt = processedAt;
        this.attempt = attempt;
        this.createdAt = createdAt;
    }

    public static PgOutbox create(Long id, String topic, String key, String payload, String headersJson) {
        Instant now = Instant.now();
        return new PgOutbox(id, topic, key, payload, headersJson, now, null, 0, now);
    }

    public static PgOutbox createWithAvailableAt(
            Long id, String topic, String key, String payload, String headersJson, Instant availableAt) {
        return new PgOutbox(id, topic, key, payload, headersJson, availableAt, null, 0, Instant.now());
    }

    public static PgOutbox of(
            Long id,
            String topic,
            String key,
            String payload,
            String headersJson,
            Instant availableAt,
            Instant processedAt,
            int attempt,
            Instant createdAt) {
        return new PgOutbox(id, topic, key, payload, headersJson, availableAt, processedAt, attempt, createdAt);
    }

    public Long getId() {
        return id;
    }

    public String getTopic() {
        return topic;
    }

    public String getKey() {
        return key;
    }

    public String getPayload() {
        return payload;
    }

    public String getHeadersJson() {
        return headersJson;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public int getAttempt() {
        return attempt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isPending() {
        return processedAt == null;
    }

    public boolean isAvailableAt(Instant now) {
        return !availableAt.isAfter(now);
    }

    public void markProcessed(Instant processedAt) {
        this.processedAt = processedAt;
    }

    public void incrementAttempt() {
        this.attempt++;
    }
}
