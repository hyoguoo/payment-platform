package com.hyoguoo.paymentplatform.pg.domain;

import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * pg-service outbox 도메인 POJO.
 * JPA 엔티티(PgOutboxEntity) 와 도메인 객체를 분리해 hexagonal 경계를 유지한다.
 *
 * <p>available_at 지연 발행, attempt 재시도 횟수 추적.
 *
 * <p><b>factory only 노출 룰</b> — 외부에서 {@code allArgsBuilder()} 직접 호출 금지.
 * builder 는 factory 내부 캡슐화 용도이며 외부 호출자는 아래 factory method 만 사용한다:
 * {@code create}, {@code createWithAvailableAt}, {@code of}.
 *
 * <p>payment-service {@code PaymentOutbox} / pg-service {@code PgInbox} 와 동일 Lombok builder
 * 패턴을 채택한다. {@code allArgsBuilder()} 는 패키지 내부 factory 캡슐화 전용.
 */
@Getter
@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
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

    /**
     * 신규 outbox row 생성 — availableAt = now (즉시 처리 가능).
     * 호출자가 {@code clock.instant()} 로 얻은 {@code now} 를 전달한다.
     *
     * <p><b>Long id 제거됨</b> — RDB {@code AUTO_INCREMENT} ({@code @GeneratedValue(strategy=IDENTITY)})
     * 가 INSERT 시 id 를 채운다. 호출자가 null 을 전달할 필요 없다.
     *
     * @param topic       Kafka 토픽
     * @param key         Kafka 메시지 키 (orderId)
     * @param payload     직렬화된 이벤트/커맨드 JSON
     * @param headersJson 직렬화된 Kafka 헤더 JSON (nullable)
     * @param now         현재 Instant (clock.instant() 전달)
     */
    public static PgOutbox create(String topic, String key, String payload, String headersJson, Instant now) {
        return PgOutbox.allArgsBuilder()
                .id(null)
                .topic(topic)
                .key(key)
                .payload(payload)
                .headersJson(headersJson)
                .availableAt(now)
                .processedAt(null)
                .attempt(0)
                .createdAt(now)
                .allArgsBuild();
    }

    /**
     * 지연 발행 outbox row 생성 — availableAt = 지정 시각.
     * 호출자가 {@code clock.instant()} 로 얻은 {@code now} 를 createdAt 으로 전달한다.
     *
     * <p><b>Long id 제거됨</b> — RDB {@code AUTO_INCREMENT} ({@code @GeneratedValue(strategy=IDENTITY)})
     * 가 INSERT 시 id 를 채운다. 호출자가 null 을 전달할 필요 없다.
     *
     * @param topic       Kafka 토픽
     * @param key         Kafka 메시지 키 (orderId)
     * @param payload     직렬화된 이벤트/커맨드 JSON
     * @param headersJson 직렬화된 Kafka 헤더 JSON (nullable)
     * @param availableAt 발행 가능 시각 (재시도 backoff 기반)
     * @param now         현재 Instant (createdAt 용, clock.instant() 전달)
     */
    public static PgOutbox createWithAvailableAt(
            String topic, String key, String payload, String headersJson, Instant availableAt, Instant now) {
        return PgOutbox.allArgsBuilder()
                .id(null)
                .topic(topic)
                .key(key)
                .payload(payload)
                .headersJson(headersJson)
                .availableAt(availableAt)
                .processedAt(null)
                .attempt(0)
                .createdAt(now)
                .allArgsBuild();
    }

    /**
     * DB 복원 / test 픽스처 전용 9-arg 오버로드 — id 포함.
     * JPA 어댑터 {@code PgOutboxEntity.toDomain()} 및 테스트 픽스처에서만 사용한다.
     *
     * @param id          DB row pk (nullable — INSERT 전 픽스처 시 null)
     * @param topic       Kafka 토픽
     * @param key         Kafka 메시지 키
     * @param payload     직렬화된 이벤트/커맨드 JSON
     * @param headersJson 직렬화된 Kafka 헤더 JSON (nullable)
     * @param availableAt 발행 가능 시각
     * @param processedAt 처리 완료 시각 (nullable — 미처리 시 null)
     * @param attempt     재시도 횟수
     * @param createdAt   생성 시각
     */
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
        return PgOutbox.allArgsBuilder()
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

    public boolean isAvailableAt(Instant now) {
        return !availableAt.isAfter(now);
    }

    public void markProcessed(Instant processedAt) {
        this.processedAt = processedAt;
    }
}
