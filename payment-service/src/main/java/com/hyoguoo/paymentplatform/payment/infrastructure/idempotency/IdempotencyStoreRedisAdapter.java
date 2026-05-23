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
 * keyspace: {@code idem:{key}}. 동시 요청의 race condition 을 방지하기 위해 3단계로 처리:
 * <ol>
 *   <li>GET — 기존 값(완성 JSON)이 있으면 hit 으로 즉시 반환 (creator 호출 회피)</li>
 *   <li>SET NX EX {@value IN_PROGRESS_MARKER} — lock 획득 성공(winner)이면 creator 호출 후 값 저장.
 *       실패(loser)면 winner 의 완성 값을 polling 후 hit 반환.</li>
 *   <li>Polling — loser 는 {@value POLL_INTERVAL_MS}ms 간격으로 최대 {@value MAX_POLL_COUNT}회 재시도.</li>
 * </ol>
 * <p>
 * IN_PROGRESS_MARKER 가 남아 있는 동안은 역직렬화를 시도하지 않는다.
 * creator 가 예외를 던지면 lock 이 TTL 만료까지 유지되어 loser 들이 polling timeout 후 예외를 전파한다.
 *
 * @see IdempotencyStoreImpl 기존 Caffeine 구현 (유지, 단일 인스턴스 전용)
 */
@Primary
@Component
@RequiredArgsConstructor
public class IdempotencyStoreRedisAdapter implements IdempotencyStore {

    private static final String KEY_PREFIX = "idem:";
    private static final String IN_PROGRESS_MARKER = "__IN_PROGRESS__";
    private static final long IN_PROGRESS_TTL_SECONDS = 10L;
    private static final long POLL_INTERVAL_MS = 50L;
    private static final int MAX_POLL_COUNT = 40; // 최대 2초 대기

    private final RedisTemplate<String, String> redisTemplate;
    private final IdempotencyProperties idempotencyProperties;
    private final ObjectMapper objectMapper;

    @Override
    public IdempotencyResult<CheckoutResult> getOrCreate(String key, Supplier<CheckoutResult> creator) {
        String redisKey = KEY_PREFIX + key;
        Duration resultTtl = Duration.ofSeconds(idempotencyProperties.getExpireAfterWriteSeconds());

        // 1단계: 기존 완성 값 확인
        String existing = redisTemplate.opsForValue().get(redisKey);
        if (existing != null && !IN_PROGRESS_MARKER.equals(existing)) {
            return IdempotencyResult.hit(deserialize(existing));
        }

        // 2단계: IN_PROGRESS 마커로 lock 획득 시도
        Boolean isWinner = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, IN_PROGRESS_MARKER, Duration.ofSeconds(IN_PROGRESS_TTL_SECONDS));

        if (Boolean.TRUE.equals(isWinner)) {
            // winner: creator 호출 후 완성 값으로 교체
            CheckoutResult created = creator.get();
            String createdJson = serialize(created);
            redisTemplate.opsForValue().set(redisKey, createdJson, resultTtl);
            return IdempotencyResult.miss(created);
        }

        // 3단계: loser polling — winner 가 완성 값을 저장할 때까지 대기
        return IdempotencyResult.hit(pollForResult(redisKey));
    }

    private CheckoutResult pollForResult(String redisKey) {
        for (int i = 0; i < MAX_POLL_COUNT; i++) {
            String value = redisTemplate.opsForValue().get(redisKey);
            if (value != null && !IN_PROGRESS_MARKER.equals(value)) {
                return deserialize(value);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("멱등성 결과 대기 중 인터럽트", e);
            }
        }
        throw new IllegalStateException("멱등성 결과 대기 시간 초과: key=" + redisKey);
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
