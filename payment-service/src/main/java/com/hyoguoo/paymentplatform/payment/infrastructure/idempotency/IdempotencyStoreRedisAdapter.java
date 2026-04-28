package com.hyoguoo.paymentplatform.payment.infrastructure.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.payment.application.dto.IdempotencyResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.IdempotencyStore;
import java.time.Duration;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 멱등성 저장소 Redis 어댑터 — 클라이언트 idempotency-key 보존.
 * <p>
 * keyspace: {@code idem:{key}}. 두 단계로 처리:
 * <ol>
 *   <li>GET — 기존 값 있으면 hit 으로 즉시 반환 (creator 호출 회피)</li>
 *   <li>SET NX EX — winner 면 miss, loser 면 winner 값 fetch 후 hit</li>
 * </ol>
 *
 * @see IdempotencyStoreImpl 기존 Caffeine 구현 (유지, 단일 인스턴스 전용)
 */
@Primary
@Component
@RequiredArgsConstructor
public class IdempotencyStoreRedisAdapter implements IdempotencyStore {

    private static final String KEY_PREFIX = "idem:";

    private final RedisTemplate<String, String> redisTemplate;
    private final IdempotencyProperties idempotencyProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public IdempotencyResult<CheckoutResult> getOrCreate(String key, Supplier<CheckoutResult> creator) {
        String redisKey = KEY_PREFIX + key;

        String existingJson = redisTemplate.opsForValue().get(redisKey);
        if (existingJson != null) {
            return IdempotencyResult.hit(deserialize(existingJson));
        }

        CheckoutResult created = creator.get();
        String createdJson = serialize(created);
        Duration ttl = Duration.ofSeconds(idempotencyProperties.getExpireAfterWriteSeconds());

        Boolean isWinner = redisTemplate.opsForValue().setIfAbsent(redisKey, createdJson, ttl);
        if (Boolean.TRUE.equals(isWinner)) {
            return IdempotencyResult.miss(created);
        }
        String winnerJson = redisTemplate.opsForValue().get(redisKey);
        return IdempotencyResult.hit(deserialize(winnerJson));
    }

    private String serialize(CheckoutResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("CheckoutResult 직렬화 실패", e);
        }
    }

    private CheckoutResult deserialize(String json) {
        try {
            return objectMapper.readValue(json, CheckoutResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("CheckoutResult 역직렬화 실패", e);
        }
    }
}
