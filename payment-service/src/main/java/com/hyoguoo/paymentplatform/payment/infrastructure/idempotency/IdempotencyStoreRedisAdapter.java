package com.hyoguoo.paymentplatform.payment.infrastructure.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.payment.application.dto.IdempotencyResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.IdempotencyStore;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * ADR-16: 멱등성 저장소 Redis 어댑터.
 * <p>
 * keyspace: {@code idem:{key}}. Lua script 2단계 원자 연산으로 동시 경합 처리:
 * <ol>
 *   <li>GET script — 키 존재 시 기존 값 반환, 없으면 null</li>
 *   <li>SET NX script — "SET key value NX EX ttl". winner만 설정 성공,
 *       loser는 winner 값 반환</li>
 * </ol>
 *
 * @see IdempotencyStoreImpl 기존 Caffeine 구현 (유지, 단일 인스턴스 전용)
 */
@Primary
@Component
@RequiredArgsConstructor
public class IdempotencyStoreRedisAdapter implements IdempotencyStore {

    private static final String KEY_PREFIX = "idem:";

    /**
     * Lua script 1: GET — 키 존재 시 현재 값 반환, 없으면 nil(null).
     */
    private static final RedisScript<String> GET_SCRIPT = new DefaultRedisScript<>(
            "local cur = redis.call('GET', KEYS[1]); if cur then return cur; else return nil; end",
            String.class
    );

    /**
     * Lua script 2: SET NX — "SET key value NX EX ttl". winner는 ARGV[1] 반환,
     * loser는 이미 저장된 값 반환.
     */
    private static final RedisScript<String> SET_NX_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2]) then return ARGV[1]; else return redis.call('GET', KEYS[1]); end",
            String.class
    );

    private final RedisTemplate<String, String> redisTemplate;
    private final IdempotencyProperties idempotencyProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public IdempotencyResult<CheckoutResult> getOrCreate(String key, Supplier<CheckoutResult> creator) {
        String redisKey = KEY_PREFIX + key;

        String existingJson = redisTemplate.execute(GET_SCRIPT, List.of(redisKey));
        if (existingJson != null) {
            return IdempotencyResult.hit(deserialize(existingJson));
        }

        CheckoutResult created = creator.get();
        String createdJson = serialize(created);
        String ttlString = String.valueOf(idempotencyProperties.getExpireAfterWriteSeconds());

        String storedJson = redisTemplate.execute(SET_NX_SCRIPT, List.of(redisKey), createdJson, ttlString);

        if (createdJson.equals(storedJson)) {
            return IdempotencyResult.miss(created);
        }
        return IdempotencyResult.hit(deserialize(storedJson));
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
