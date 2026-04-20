package com.hyoguoo.paymentplatform.payment.infrastructure.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.IdempotencyResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@DisplayName("IdempotencyStoreRedisAdapter 테스트")
class IdempotencyStoreRedisAdapterTest {

    private RedisTemplate<String, String> mockRedisTemplate;
    private IdempotencyProperties idempotencyProperties;
    private IdempotencyStoreRedisAdapter adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mockRedisTemplate = Mockito.mock(RedisTemplate.class);
        idempotencyProperties = new IdempotencyProperties(10000L, 30L);
        adapter = new IdempotencyStoreRedisAdapter(mockRedisTemplate, idempotencyProperties);
    }

    @Test
    @DisplayName("키가 없을 때 creator를 1회 호출하고 결과를 저장한 후 miss를 반환한다")
    @SuppressWarnings("unchecked")
    void getOrCreate_WhenKeyAbsent_ShouldInvokeCreatorAndStoreResult() {
        // given
        CheckoutResult expected = CheckoutResult.builder()
                .orderId("order-001")
                .totalAmount(BigDecimal.valueOf(10000))
                .isDuplicate(false)
                .build();
        Supplier<CheckoutResult> creator = Mockito.mock(Supplier.class);
        given(creator.get()).willReturn(expected);

        // GET script: null 반환 (키 없음)
        given(mockRedisTemplate.execute(any(RedisScript.class), anyList()))
                .willReturn(null);
        // SET NX script: ARGV[1](put value) 반환 (winner — 내 값으로 설정됨)
        given(mockRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .willAnswer(invocation -> {
                    Object[] args = invocation.getArguments();
                    // ARGV[1]은 세 번째 vararg
                    return args[2];
                });

        // when
        IdempotencyResult<CheckoutResult> result = adapter.getOrCreate("test-key", creator);

        // then
        assertThat(result.isDuplicate()).isFalse();
        assertThat(result.getValue().getOrderId()).isEqualTo("order-001");
        then(creator).should(times(1)).get();
        // GET script 1회 + SET NX script 1회 = execute 2회
        then(mockRedisTemplate).should(times(1)).execute(any(RedisScript.class), anyList());
        then(mockRedisTemplate).should(times(1)).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    @DisplayName("키가 이미 존재할 때 creator를 호출하지 않고 캐시된 결과와 hit를 반환한다")
    @SuppressWarnings("unchecked")
    void getOrCreate_WhenKeyPresent_ShouldReturnCachedResultWithoutCreator() {
        // given
        CheckoutResult stored = CheckoutResult.builder()
                .orderId("order-002")
                .totalAmount(BigDecimal.valueOf(20000))
                .isDuplicate(true)
                .build();
        Supplier<CheckoutResult> creator = Mockito.mock(Supplier.class);

        // GET script: JSON 직렬화된 값 반환 (키 존재)
        // 실제 직렬화 형식은 adapter가 결정하므로, adapter가 역직렬화할 수 있는 형식으로 mock
        // adapter에서 ObjectMapper를 사용하여 직렬화/역직렬화하므로 실제 JSON 문자열 필요
        String storedJson = "{\"orderId\":\"order-002\",\"totalAmount\":20000,\"isDuplicate\":true}";
        given(mockRedisTemplate.execute(any(RedisScript.class), anyList()))
                .willReturn(storedJson);

        // when
        IdempotencyResult<CheckoutResult> result = adapter.getOrCreate("test-key-2", creator);

        // then
        assertThat(result.isDuplicate()).isTrue();
        assertThat(result.getValue().getOrderId()).isEqualTo("order-002");
        then(creator).should(never()).get();
        // SET NX script는 호출되지 않아야 함
        then(mockRedisTemplate).should(never()).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    @DisplayName("동시 miss 경합 시 creator는 1회만 호출되고 winner 값을 hit으로 반환한다")
    @SuppressWarnings("unchecked")
    void getOrCreate_ConcurrentMiss_ShouldInvokeCreatorOnce() {
        // given
        CheckoutResult myValue = CheckoutResult.builder()
                .orderId("order-mine")
                .totalAmount(BigDecimal.valueOf(10000))
                .isDuplicate(false)
                .build();
        CheckoutResult winnerValue = CheckoutResult.builder()
                .orderId("order-winner")
                .totalAmount(BigDecimal.valueOf(10000))
                .isDuplicate(false)
                .build();
        Supplier<CheckoutResult> creator = Mockito.mock(Supplier.class);
        given(creator.get()).willReturn(myValue);

        // GET script: null 반환 (키 없음 — 동시에 두 스레드가 miss)
        given(mockRedisTemplate.execute(any(RedisScript.class), anyList()))
                .willReturn(null);
        // SET NX script: winner가 먼저 설정 → 다른 값(winner JSON) 반환
        String winnerJson = "{\"orderId\":\"order-winner\",\"totalAmount\":10000,\"isDuplicate\":false}";
        given(mockRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .willReturn(winnerJson);

        // when
        IdempotencyResult<CheckoutResult> result = adapter.getOrCreate("concurrent-key", creator);

        // then — winner의 값을 hit으로 반환
        assertThat(result.isDuplicate()).isTrue();
        assertThat(result.getValue().getOrderId()).isEqualTo("order-winner");
        // creator는 1회만 호출 (loser도 creator 호출 후 SET NX 시도)
        then(creator).should(times(1)).get();
    }

    @Test
    @DisplayName("SET NX 호출 시 ARGV[2]에 expireAfterWriteSeconds 값이 전달된다")
    @SuppressWarnings("unchecked")
    void getOrCreate_ShouldRespectExpireAfterWriteSeconds() {
        // given
        CheckoutResult result = CheckoutResult.builder()
                .orderId("order-ttl")
                .totalAmount(BigDecimal.valueOf(5000))
                .isDuplicate(false)
                .build();
        Supplier<CheckoutResult> creator = Mockito.mock(Supplier.class);
        given(creator.get()).willReturn(result);

        given(mockRedisTemplate.execute(any(RedisScript.class), anyList()))
                .willReturn(null);

        ArgumentCaptor<Object> argCaptor = ArgumentCaptor.forClass(Object.class);
        given(mockRedisTemplate.execute(any(RedisScript.class), anyList(), argCaptor.capture(), argCaptor.capture()))
                .willAnswer(invocation -> invocation.getArgument(2));

        // when
        adapter.getOrCreate("ttl-key", creator);

        // then — 두 번째 캡처된 값(ARGV[2])이 expireAfterWriteSeconds(30)
        List<Object> capturedArgs = argCaptor.getAllValues();
        assertThat(capturedArgs).hasSize(2);
        // ARGV[2] = TTL seconds (String 또는 Long)
        Object ttlArg = capturedArgs.get(1);
        assertThat(ttlArg.toString()).isEqualTo("30");
    }
}
