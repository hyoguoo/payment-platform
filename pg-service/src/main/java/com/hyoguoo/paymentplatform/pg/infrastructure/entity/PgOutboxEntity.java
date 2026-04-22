package com.hyoguoo.paymentplatform.pg.infrastructure.entity;

import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * pg_outbox 테이블 JPA 엔티티.
 * V1__pg_schema.sql의 (topic/key/payload/available_at/processed_at/attempt/created_at) 스키마와 매핑된다.
 * ADR-30: available_at 기반 지연 발행 + attempt 재시도 카운트.
 *
 * <p>컬럼 "key"는 MySQL 예약어여서 백틱으로 감싼다.
 */
@Getter
@Entity
@Table(name = "pg_outbox")
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PgOutboxEntity {

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
    private Integer attempt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static PgOutboxEntity from(PgOutbox outbox) {
        return PgOutboxEntity.builder()
                .id(outbox.getId())
                .topic(outbox.getTopic())
                .key(outbox.getKey())
                .payload(outbox.getPayload())
                .headersJson(outbox.getHeadersJson())
                .availableAt(LocalDateTime.ofInstant(outbox.getAvailableAt(), ZoneOffset.UTC))
                .processedAt(outbox.getProcessedAt() == null
                        ? null : LocalDateTime.ofInstant(outbox.getProcessedAt(), ZoneOffset.UTC))
                .attempt(outbox.getAttempt())
                .createdAt(LocalDateTime.ofInstant(outbox.getCreatedAt(), ZoneOffset.UTC))
                .build();
    }

    public PgOutbox toDomain() {
        return PgOutbox.of(
                id,
                topic,
                key,
                payload,
                headersJson,
                availableAt.toInstant(ZoneOffset.UTC),
                processedAt == null ? null : processedAt.toInstant(ZoneOffset.UTC),
                attempt,
                createdAt.toInstant(ZoneOffset.UTC)
        );
    }
}
