package com.hyoguoo.paymentplatform.payment.infrastructure.dedupe;

import com.hyoguoo.paymentplatform.payment.application.port.out.EventDedupeStore;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * EventDedupeStore Redis 구현체. ADR-04(2단 멱등성 키).
 * SET NX EX 패턴으로 eventUuid별 단일 소비를 보장한다.
 *
 * <p>keyspace: {@code evt:seen:{uuid}}.
 * TTL: {@code payment.event-dedupe.ttl}. 기본 8일 — Kafka retention(7d) + 복구 버퍼(1d) = P8D.
 * product-service StockRestoreUseCase.DEDUPE_TTL = Duration.ofDays(8) 과 정렬.
 *
 * <p>활성 조건: {@code spring.data.redis.host} 속성이 설정되어 있을 때만 등록.
 * 테스트(JPA/Redis autoconfig 제외)에서는 FakeEventDedupeStore를 사용한다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.data.redis.host")
public class EventDedupeStoreRedisAdapter implements EventDedupeStore {

    private static final String KEY_PREFIX = "evt:seen:";
    private static final String MARKER = "1";

    private final StringRedisTemplate redisTemplate;

    @Value("${payment.event-dedupe.ttl:P8D}")
    private Duration ttl;

    @Override
    public boolean markSeen(String eventUuid) {
        Boolean firstSeen = redisTemplate
                .opsForValue()
                .setIfAbsent(KEY_PREFIX + eventUuid, MARKER, ttl);
        return Boolean.TRUE.equals(firstSeen);
    }

    @Override
    public void remove(String eventUuid) {
        redisTemplate.delete(KEY_PREFIX + eventUuid);
    }
}
