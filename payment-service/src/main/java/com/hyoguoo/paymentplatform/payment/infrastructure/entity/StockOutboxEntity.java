package com.hyoguoo.paymentplatform.payment.infrastructure.entity;

import com.hyoguoo.paymentplatform.payment.domain.StockOutbox;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * stock_outbox 테이블 JPA 엔티티 — stock commit/restore 이벤트의 transactional outbox row.
 *
 * <p>pg-service {@code PgOutboxEntity} 와 동격 구조이지만 공유 JAR 없이 독립 복제한다.
 * V2__stock_outbox.sql 스키마와 1:1 매핑이며, payment_outbox 와 달리 order_id UNIQUE 제약이 없다 —
 * 한 주문이 여러 productId 에 대해 별도 row 를 INSERT 하기 때문이다.
 *
 * <p>컬럼 "key"는 MySQL 예약어여서 백틱으로 감싼다.
 */
@Getter
@Entity
@Table(
        name = "stock_outbox",
        indexes = {
                @Index(name = "idx_stock_outbox_processed_available", columnList = "processed_at, available_at")
        }
)
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class StockOutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "topic", nullable = false, length = 200)
    private String topic;

    @Column(name = "`key`", nullable = false, length = 100)
    private String key;

    @Column(name = "payload", nullable = false, columnDefinition = "LONGTEXT")
    private String payload;

    @Column(name = "headers_json", columnDefinition = "TEXT")
    private String headersJson;

    @Column(name = "available_at", nullable = false)
    private LocalDateTime availableAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static StockOutboxEntity from(StockOutbox outbox) {
        return StockOutboxEntity.builder()
                .id(outbox.getId())
                .topic(outbox.getTopic())
                .key(outbox.getKey())
                .payload(outbox.getPayload())
                .headersJson(outbox.getHeadersJson())
                .availableAt(outbox.getAvailableAt())
                .processedAt(outbox.getProcessedAt())
                .attempt(outbox.getAttempt())
                .createdAt(outbox.getCreatedAt())
                .build();
    }

    public StockOutbox toDomain() {
        return StockOutbox.of(
                id, topic, key, payload, headersJson,
                availableAt, processedAt, attempt, createdAt
        );
    }
}
