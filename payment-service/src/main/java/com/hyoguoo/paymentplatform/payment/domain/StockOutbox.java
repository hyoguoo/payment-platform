package com.hyoguoo.paymentplatform.payment.domain;

import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * stock_outbox 테이블 도메인 POJO.
 * T-J1: stock commit/restore Kafka 발행 Transactional Outbox 패턴 적용.
 *
 * <p>ADR-19 복제(b): pg-service {@code PgOutbox} 구조를 독립 복제 (pg-service 직접 import 금지).
 * <p>pg_outbox 와 동일 구조 — id(PK), topic, key, payload(JSON String),
 * available_at, processed_at, attempt, created_at.
 * payment_outbox 의 order_id UNIQUE 제약 없음 — 동일 주문의 다중 productId 수용.
 *
 * <p>K6: PaymentEvent/PgOutbox 일관 Lombok 패턴 적용.
 * mutable 필드(processedAt, attempt)는 도메인 의미 메서드(markProcessed/incrementAttempt)로만 변경한다.
 */
@Getter
@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class StockOutbox {

    private Long id;
    private String topic;
    private String key;
    private String payload;
    private String headersJson;
    private LocalDateTime availableAt;
    private LocalDateTime processedAt;
    private int attempt;
    private LocalDateTime createdAt;

    /**
     * 신규 outbox row 생성 (INSERT용).
     * id=null — DB AUTO_INCREMENT가 할당한다.
     */
    public static StockOutbox create(String topic, String key, String payload, LocalDateTime now) {
        return StockOutbox.allArgsBuilder()
                .id(null)
                .topic(topic)
                .key(key)
                .payload(payload)
                .headersJson(null)
                .availableAt(now)
                .processedAt(null)
                .attempt(0)
                .createdAt(now)
                .allArgsBuild();
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
        return StockOutbox.allArgsBuilder()
                .id(id)
                .topic(topic)
                .key(key)
                .payload(payload)
                .headersJson(headersJson)
                .availableAt(availableAt)
                .processedAt(processedAt)
                .attempt(attempt)
                .createdAt(createdAt)
                .allArgsBuild();
    }

    public boolean isPending() {
        return processedAt == null;
    }

    /**
     * 발행 완료 시각을 기록한다 (도메인 의미 메서드).
     */
    public void markProcessed(LocalDateTime now) {
        this.processedAt = now;
    }

    /**
     * 재시도 횟수를 1 증가시킨다 (도메인 의미 메서드).
     */
    public void incrementAttempt() {
        this.attempt++;
    }
}
