package com.hyoguoo.paymentplatform.payment.infrastructure.dedupe;

import com.hyoguoo.paymentplatform.payment.application.port.out.EventDedupeStore;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * EventDedupeStore Redis 구현체.
 *
 * <p>two-phase lease 패턴:
 * <ul>
 *   <li>markWithLease: SET NX EX shortTtl — 처리 권한 예약</li>
 *   <li>extendLease: SET XX EX longTtl — 성공 후 TTL 연장</li>
 *   <li>remove: DEL — boolean(삭제 성공 여부) 반환</li>
 * </ul>
 *
 * <p>keyspace: {@code evt:seen:{uuid}}.
 * TTL: {@code payment.event-dedupe.ttl}. 기본 8일 — Kafka retention(7d) + 복구 버퍼(1d) = P8D.
 * product-service {@code StockCommitUseCase.DEDUPE_TTL = Duration.ofDays(8)} 과 정렬.
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

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${payment.event-dedupe.ttl:P8D}")
    private Duration ttl;

    /**
     * shortTtl 동안 처리 권한을 예약한다. SET NX EX shortTtl.
     *
     * @return true — 처리 권한 획득, false — 이미 처리 중(NX 실패)
     */
    @Override
    public boolean markWithLease(String eventUuid, Duration shortTtl) {
        Boolean firstSeen = stringRedisTemplate
                .opsForValue()
                .setIfAbsent(KEY_PREFIX + eventUuid, MARKER, shortTtl);
        return Boolean.TRUE.equals(firstSeen);
    }

    /**
     * 성공 후 dedupe 키를 longTtl로 연장한다. SET XX EX longTtl.
     *
     * @return true — 연장 성공, false — 키 없음(Redis flap 등)
     */
    @Override
    public boolean extendLease(String eventUuid, Duration longTtl) {
        Boolean updated = stringRedisTemplate
                .opsForValue()
                .setIfPresent(KEY_PREFIX + eventUuid, MARKER, longTtl);
        return Boolean.TRUE.equals(updated);
    }

    /**
     * dedupe 기록을 삭제한다.
     *
     * @return true — 삭제 성공, false — 키 없음 또는 Redis 오류
     */
    @Override
    public boolean remove(String eventUuid) {
        Boolean deleted = stringRedisTemplate.delete(KEY_PREFIX + eventUuid);
        return Boolean.TRUE.equals(deleted);
    }
}
