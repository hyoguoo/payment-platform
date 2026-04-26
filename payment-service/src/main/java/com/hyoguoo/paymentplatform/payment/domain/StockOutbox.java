package com.hyoguoo.paymentplatform.payment.domain;

import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * stock_outbox 테이블 도메인 POJO — stock commit/restore Kafka 발행을 위한 transactional outbox row.
 *
 * <p>pg-service {@code PgOutbox} 와 동격 구조이지만 공유 JAR 없이 독립 복제한다(pg-service 직접 import 금지).
 * pg_outbox 와 동일하게 id(PK) / topic / key / payload(JSON String) / available_at / processed_at /
 * attempt / created_at 필드를 갖는다. payment_outbox 와 달리 order_id UNIQUE 제약은 없다 —
 * 한 주문이 여러 productId 에 대해 별도 row 를 갖는다.
 *
 * <p>PaymentEvent / PgOutbox 와 일관된 Lombok 패턴을 쓴다.
 * mutable 필드(processedAt, attempt) 는 도메인 의미 메서드(markProcessed / incrementAttempt) 로만 변경한다.
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
