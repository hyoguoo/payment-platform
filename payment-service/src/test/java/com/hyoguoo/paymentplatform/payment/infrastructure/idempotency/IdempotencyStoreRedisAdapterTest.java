package com.hyoguoo.paymentplatform.payment.infrastructure.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.payment.application.dto.IdempotencyResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.CheckoutResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@DisplayName("IdempotencyStoreRedisAdapter 테스트")
class IdempotencyStoreRedisAdapterTest {

    private RedisTemplate<String, String> mockRedisTemplate;
    private ValueOperations<String, String> mockValueOps;
    private IdempotencyProperties idempotencyProperties;
    private IdempotencyStoreRedisAdapter adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mockRedisTemplate = Mockito.mock(RedisTemplate.class);
        mockValueOps = Mockito.mock(ValueOperations.class);
        given(mockRedisTemplate.opsForValue()).willReturn(mockValueOps);

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

        given(mockValueOps.get("idem:test-key")).willReturn(null);
        given(mockValueOps.setIfAbsent(eq("idem:test-key"), any(String.class), any(Duration.class)))
                .willReturn(true);

        // when
        IdempotencyResult<CheckoutResult> result = adapter.getOrCreate("test-key", creator);

        // then
        assertThat(result.isDuplicate()).isFalse();
        assertThat(result.getValue().getOrderId()).isEqualTo("order-001");
        then(creator).should(times(1)).get();
        then(mockValueOps).should(times(1)).get("idem:test-key");
        then(mockValueOps).should(times(1))
                .setIfAbsent(eq("idem:test-key"), any(String.class), any(Duration.class));
    }

    @Test
    @DisplayName("키가 이미 존재할 때 creator를 호출하지 않고 캐시된 결과와 hit를 반환한다")
    @SuppressWarnings("unchecked")
    void getOrCreate_WhenKeyPresent_ShouldReturnCachedResultWithoutCreator() {
        // given
        Supplier<CheckoutResult> creator = Mockito.mock(Supplier.class);

        String storedJson = "{\"orderId\":\"order-002\",\"totalAmount\":20000,\"isDuplicate\":true}";
        given(mockValueOps.get("idem:test-key-2")).willReturn(storedJson);

        // when
        IdempotencyResult<CheckoutResult> result = adapter.getOrCreate("test-key-2", creator);

        // then
        assertThat(result.isDuplicate()).isTrue();
        assertThat(result.getValue().getOrderId()).isEqualTo("order-002");
        then(creator).should(never()).get();
        then(mockValueOps).should(never())
                .setIfAbsent(any(String.class), any(String.class), any(Duration.class));
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
        Supplier<CheckoutResult> creator = Mockito.mock(Supplier.class);
        given(creator.get()).willReturn(myValue);

        // GET 첫 호출(phase 1) null, 두 번째(loser fallback) winnerJson
        String winnerJson = "{\"orderId\":\"order-winner\",\"totalAmount\":10000,\"isDuplicate\":false}";
        given(mockValueOps.get("idem:concurrent-key"))
                .willReturn(null)
                .willReturn(winnerJson);
        // SET NX 실패 (loser)
        given(mockValueOps.setIfAbsent(eq("idem:concurrent-key"), any(String.class), any(Duration.class)))
                .willReturn(false);

        // when
        IdempotencyResult<CheckoutResult> result = adapter.getOrCreate("concurrent-key", creator);

        // then — winner의 값을 hit으로 반환
        assertThat(result.isDuplicate()).isTrue();
        assertThat(result.getValue().getOrderId()).isEqualTo("order-winner");
        then(creator).should(times(1)).get();
        then(mockValueOps).should(times(2)).get("idem:concurrent-key");
    }

    @Test
    @DisplayName("setIfAbsent 호출 시 expireAfterWriteSeconds 가 Duration 으로 전달된다")
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

        given(mockValueOps.get("idem:ttl-key")).willReturn(null);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        given(mockValueOps.setIfAbsent(eq("idem:ttl-key"), any(String.class), ttlCaptor.capture()))
                .willReturn(true);

        // when
        adapter.getOrCreate("ttl-key", creator);

        // then — TTL 이 IdempotencyProperties 의 expireAfterWriteSeconds(30) 와 일치
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofSeconds(30));
    }
}
