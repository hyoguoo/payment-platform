package com.hyoguoo.paymentplatform.payment.infrastructure.cache;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCompensationAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockDecrementAtomicResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 재고 캐시 Redis 어댑터 — payment-service 의 선차감 캐시.
 *
 * <p>keyspace: {@code stock:{productId}}.
 * Lua 스크립트로 DECRBY → 음수 감지 → INCRBY 복구를 원자적으로 수행한다.
 * AOF(appendonly yes) 전제 하에 재시작 복원이 보장된다.
 * Redis 연결 실패 시 예외를 그대로 전파한다 — QUARANTINED 분기 결정은 상위 계층 책임.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockCacheRedisAdapter implements StockCachePort {

    private static final String KEY_PREFIX = "stock:";
    private static final String DEDUP_DECREMENT_PREFIX = "decrement:done:";
    private static final String DEDUP_COMPENSATION_PREFIX = "compensation:done:";
    private static final long DEDUP_TTL_SECONDS = 691200L; // P8D

    private static final DefaultRedisScript<Long> DECREMENT_SCRIPT;
    private static final DefaultRedisScript<String> DECREMENT_ATOMIC_SCRIPT;
    private static final DefaultRedisScript<String> COMPENSATION_ATOMIC_SCRIPT;

    static {
        DECREMENT_SCRIPT = new DefaultRedisScript<>();
        DECREMENT_SCRIPT.setLocation(new ClassPathResource("lua/stock_decrement.lua"));
        DECREMENT_SCRIPT.setResultType(Long.class);

        DECREMENT_ATOMIC_SCRIPT = new DefaultRedisScript<>();
        DECREMENT_ATOMIC_SCRIPT.setLocation(new ClassPathResource("lua/stock_decrement_atomic.lua"));
        DECREMENT_ATOMIC_SCRIPT.setResultType(String.class);

        COMPENSATION_ATOMIC_SCRIPT = new DefaultRedisScript<>();
        COMPENSATION_ATOMIC_SCRIPT.setLocation(new ClassPathResource("lua/stock_compensation_atomic.lua"));
        COMPENSATION_ATOMIC_SCRIPT.setResultType(String.class);
    }

    private final StringRedisTemplate stockCacheRedisTemplate;

    /**
     * 결제 단위 atomic 선차감.
     *
     * <p>KEYS = [decrement:done:{orderId}, stock:{prod1}, stock:{prod2}, ...]
     * ARGV  = [qty1, qty2, ..., 691200]
     * Lua 결과 문자열 → {@link StockDecrementAtomicResult} enum 변환.
     * 인프라 장애 시 RuntimeException 그대로 전파.
     */
    @Override
    public StockDecrementAtomicResult decrementAtomic(String orderId, List<PaymentOrder> paymentOrders) {
        List<String> keys = buildDecrementKeys(orderId, paymentOrders);
        String[] argv = buildArgv(paymentOrders);
        String luaResult = stockCacheRedisTemplate.execute(DECREMENT_ATOMIC_SCRIPT, keys, argv);
        return StockDecrementAtomicResult.valueOf(luaResult);
    }

    /**
     * 결제 단위 atomic 보상(복원).
     *
     * <p>KEYS = [compensation:done:{orderId}, stock:{prod1}, stock:{prod2}, ...]
     * ARGV  = [qty1, qty2, ..., 691200]
     * Lua 결과 문자열 → {@link StockCompensationAtomicResult} enum 변환.
     * 인프라 장애 시 RuntimeException 그대로 전파.
     */
    @Override
    public StockCompensationAtomicResult compensateAtomic(String orderId, List<PaymentOrder> paymentOrders) {
        List<String> keys = buildCompensationKeys(orderId, paymentOrders);
        String[] argv = buildArgv(paymentOrders);
        String luaResult = stockCacheRedisTemplate.execute(COMPENSATION_ATOMIC_SCRIPT, keys, argv);
        return StockCompensationAtomicResult.valueOf(luaResult);
    }

    private List<String> buildDecrementKeys(String orderId, List<PaymentOrder> paymentOrders) {
        List<String> keys = new ArrayList<>();
        keys.add(DEDUP_DECREMENT_PREFIX + orderId);
        for (PaymentOrder order : paymentOrders) {
            keys.add(KEY_PREFIX + order.getProductId());
        }
        return keys;
    }

    private List<String> buildCompensationKeys(String orderId, List<PaymentOrder> paymentOrders) {
        List<String> keys = new ArrayList<>();
        keys.add(DEDUP_COMPENSATION_PREFIX + orderId);
        for (PaymentOrder order : paymentOrders) {
            keys.add(KEY_PREFIX + order.getProductId());
        }
        return keys;
    }

    private String[] buildArgv(List<PaymentOrder> paymentOrders) {
        String[] argv = new String[paymentOrders.size() + 1];
        for (int i = 0; i < paymentOrders.size(); i++) {
            argv[i] = String.valueOf(paymentOrders.get(i).getQuantity());
        }
        argv[paymentOrders.size()] = String.valueOf(DEDUP_TTL_SECONDS);
        return argv;
    }

    @Override
    public boolean decrement(Long productId, int quantity) {
        Long result = stockCacheRedisTemplate.execute(
                DECREMENT_SCRIPT,
                List.of(KEY_PREFIX + productId),
                String.valueOf(quantity)
        );
        return result != null && result == 1L;
    }

    @Override
    public void rollback(Long productId, int quantity) {
        stockCacheRedisTemplate.opsForValue().increment(KEY_PREFIX + productId, quantity);
    }

    @Override
    public void increment(Long productId, int quantity) {
        stockCacheRedisTemplate.opsForValue().increment(KEY_PREFIX + productId, quantity);
    }

    @Override
    public int current(Long productId) {
        String value = stockCacheRedisTemplate.opsForValue().get(KEY_PREFIX + productId);
        return value == null ? 0 : Integer.parseInt(value);
    }

    @Override
    public Optional<Integer> findCurrent(Long productId) {
        String value = stockCacheRedisTemplate.opsForValue().get(KEY_PREFIX + productId);
        return value == null ? Optional.empty() : Optional.of(Integer.parseInt(value));
    }

    @Override
    public void set(Long productId, int quantity) {
        stockCacheRedisTemplate.opsForValue().set(KEY_PREFIX + productId, String.valueOf(quantity));
    }
}
