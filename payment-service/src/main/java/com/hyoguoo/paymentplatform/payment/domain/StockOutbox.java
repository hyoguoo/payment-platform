package com.hyoguoo.paymentplatform.payment.domain;

import java.time.LocalDateTime;

/**
 * stock_outbox 테이블 도메인 POJO.
 * T-J1: stock commit/restore Kafka 발행 Transactional Outbox 패턴 적용.
 *
 * <p>ADR-19 복제(b): pg-service {@code PgOutbox} 구조를 독립 복제 (pg-service 직접 import 금지).
 * <p>pg_outbox 와 동일 구조 — id(PK), topic, key, payload(JSON String),
 * available_at, processed_at, attempt, created_at.
 * payment_outbox 의 order_id UNIQUE 제약 없음 — 동일 주문의 다중 productId 수용.
 */
public class StockOutbox {

    private final Long id;
    private final String topic;
    private final String key;
    private final String payload;
    private final String headersJson;
    private final LocalDateTime availableAt;
    private LocalDateTime processedAt;
    private int attempt;
    private final LocalDateTime createdAt;

    private StockOutbox(
            Long id,
            String topic,
            String key,
            String payload,
            String headersJson,
            LocalDateTime availableAt,
            LocalDateTime processedAt,
            int attempt,
            LocalDateTime createdAt) {
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

    /**
     * 신규 outbox row 생성 (INSERT용).
     * id=null — DB AUTO_INCREMENT가 할당한다.
     */
    public static StockOutbox create(String topic, String key, String payload, LocalDateTime now) {
        return new StockOutbox(null, topic, key, payload, null, now, null, 0, now);
    }

    /**
     * DB 조회 결과를 도메인 객체로 복원 (SELECT용).
     */
    public static StockOutbox of(
            Long id,
            String topic,
            String key,
            String payload,
            String headersJson,
            LocalDateTime availableAt,
            LocalDateTime processedAt,
            int attempt,
            LocalDateTime createdAt) {
        return new StockOutbox(id, topic, key, payload, headersJson,
                availableAt, processedAt, attempt, createdAt);
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

    public LocalDateTime getAvailableAt() {
        return availableAt;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public int getAttempt() {
        return attempt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public boolean isPending() {
        return processedAt == null;
    }

    public void markProcessed(LocalDateTime now) {
        this.processedAt = now;
    }

    public void incrementAttempt() {
        this.attempt++;
    }
}
