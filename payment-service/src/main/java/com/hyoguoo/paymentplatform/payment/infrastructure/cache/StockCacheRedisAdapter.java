package com.hyoguoo.paymentplatform.payment.infrastructure.cache;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockDecrementAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRecoveryCompensationResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 재고 캐시 Redis 어댑터 — payment-service 의 선차감 캐시.
 *
 * <p>keyspace: {@code stock:{productId}} — 상품 번호를 Redis Cluster 해시태그로 감싸, 한 상품에
 * 속한 재고·선차감 표시·되돌리기 표시가 항상 같은 슬롯(노드)에 모이게 한다.
 * Lua 스크립트는 상품 하나만 원자적으로 다루고, 결제 단위(여러 상품) 조립은 호출자
 * ({@code PaymentTransactionCoordinator} 등)가 상품별로 이 어댑터를 반복 호출해 담당한다.
 *
 * <p><b>주문 단위 선점</b>: {@link #acquireOrderLock}/{@link #releaseOrderLock} 은 상품 키와
 * 별개인 {@code stock:order-lock:orderId} 키를 쓴다. 동시 중복 확정 요청이 상품별로 승자가
 * 갈리는 것을 막기 위해 상품 반복 앞에서 한 번 잡고 반복이 끝나면 명시적으로 푼다.
 *
 * <p>AOF(appendonly yes) 전제 하에 재시작 복원이 보장된다.
 * Redis 연결 실패 시 예외를 그대로 전파한다 — QUARANTINED 분기 결정은 상위 계층 책임.
 */
@Component
public class StockCacheRedisAdapter implements StockCachePort {

    private static final String KEY_PREFIX = "stock:{";
    private static final String KEY_SUFFIX = "}";
    private static final String DEDUP_DECREMENT_PREFIX = "decrement:done:{";
    private static final String DEDUP_COMPENSATION_PREFIX = "compensation:done:{";
    private static final long DEDUP_TTL_SECONDS = 691200L; // P8D
    private static final String ORDER_LOCK_KEY_PREFIX = "stock:order-lock:";

    private static final DefaultRedisScript<String> DECREMENT_ATOMIC_SCRIPT;
    private static final DefaultRedisScript<String> COMPENSATION_IF_DECREMENTED_SCRIPT;
    private static final DefaultRedisScript<String> REJECT_COMPENSATION_SCRIPT;
    private static final DefaultRedisScript<String> ORDER_LOCK_ACQUIRE_SCRIPT;
    private static final DefaultRedisScript<Long> ORDER_LOCK_RELEASE_SCRIPT;

    static {
        DECREMENT_ATOMIC_SCRIPT = new DefaultRedisScript<>();
        DECREMENT_ATOMIC_SCRIPT.setLocation(new ClassPathResource("lua/stock_decrement_atomic.lua"));
        DECREMENT_ATOMIC_SCRIPT.setResultType(String.class);

        COMPENSATION_IF_DECREMENTED_SCRIPT = new DefaultRedisScript<>();
        COMPENSATION_IF_DECREMENTED_SCRIPT.setLocation(
                new ClassPathResource("lua/stock_compensation_if_decremented.lua"));
        COMPENSATION_IF_DECREMENTED_SCRIPT.setResultType(String.class);

        REJECT_COMPENSATION_SCRIPT = new DefaultRedisScript<>();
        REJECT_COMPENSATION_SCRIPT.setLocation(new ClassPathResource("lua/stock_reject_compensation.lua"));
        REJECT_COMPENSATION_SCRIPT.setResultType(String.class);

        ORDER_LOCK_ACQUIRE_SCRIPT = new DefaultRedisScript<>();
        ORDER_LOCK_ACQUIRE_SCRIPT.setLocation(new ClassPathResource("lua/stock_order_lock_acquire.lua"));
        ORDER_LOCK_ACQUIRE_SCRIPT.setResultType(String.class);

        ORDER_LOCK_RELEASE_SCRIPT = new DefaultRedisScript<>();
        ORDER_LOCK_RELEASE_SCRIPT.setLocation(new ClassPathResource("lua/stock_order_lock_release.lua"));
        ORDER_LOCK_RELEASE_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stockCacheRedisTemplate;
    private final long orderLockTtlSeconds;

    public StockCacheRedisAdapter(
            StringRedisTemplate stockCacheRedisTemplate,
            @Value("${payment.cache.order-lock.ttl-seconds:30}") long orderLockTtlSeconds) {
        this.stockCacheRedisTemplate = stockCacheRedisTemplate;
        this.orderLockTtlSeconds = orderLockTtlSeconds;
    }

    /**
     * 상품 하나에 대한 atomic 선차감.
     *
     * <p>KEYS = [decrement:done:{productId}:orderId, stock:{productId}]
     * ARGV  = [qty, 691200]
     */
    @Override
    public StockDecrementAtomicResult decrementAtomic(String orderId, PaymentOrder paymentOrder) {
        List<String> keys = List.of(
                decrementDoneKey(paymentOrder.getProductId(), orderId),
                stockKey(paymentOrder.getProductId())
        );
        String[] argv = {String.valueOf(paymentOrder.getQuantity()), String.valueOf(DEDUP_TTL_SECONDS)};
        String luaResult = stockCacheRedisTemplate.execute(DECREMENT_ATOMIC_SCRIPT, keys, argv);
        return StockDecrementAtomicResult.valueOf(luaResult);
    }

    /**
     * 상품 하나에 대한 격리 복구 전용 조건부 보상.
     *
     * <p>KEYS = [decrement:done:{productId}:orderId, compensation:done:{productId}:orderId, stock:{productId}]
     * ARGV  = [qty, 691200]
     */
    @Override
    public StockRecoveryCompensationResult compensateIfDecremented(String orderId, PaymentOrder paymentOrder) {
        List<String> keys = List.of(
                decrementDoneKey(paymentOrder.getProductId(), orderId),
                compensationDoneKey(paymentOrder.getProductId(), orderId),
                stockKey(paymentOrder.getProductId())
        );
        String[] argv = {String.valueOf(paymentOrder.getQuantity()), String.valueOf(DEDUP_TTL_SECONDS)};
        String luaResult = stockCacheRedisTemplate.execute(COMPENSATION_IF_DECREMENTED_SCRIPT, keys, argv);
        return StockRecoveryCompensationResult.valueOf(luaResult);
    }

    /**
     * 상품 하나에 대한 거절 전용 되돌리기.
     *
     * <p>재고를 복원하면서 선차감 표시와 되돌리기 표시를 함께 지워, 같은 주문번호의 재시도가
     * 이 상품을 다시 정상적으로 차감할 수 있게 한다. 호출 자체가 예외를 던지면 삼키지 않고
     * 그대로 전파한다 — 어느 상품까지 되돌렸는지는 선차감 기록(후속 태스크)이 회수 근거가 된다.
     *
     * <p>KEYS = [decrement:done:{productId}:orderId, compensation:done:{productId}:orderId, stock:{productId}]
     * ARGV  = [qty]
     */
    @Override
    public void rejectCompensate(String orderId, PaymentOrder paymentOrder) {
        List<String> keys = List.of(
                decrementDoneKey(paymentOrder.getProductId(), orderId),
                compensationDoneKey(paymentOrder.getProductId(), orderId),
                stockKey(paymentOrder.getProductId())
        );
        String[] argv = {String.valueOf(paymentOrder.getQuantity())};
        stockCacheRedisTemplate.execute(REJECT_COMPENSATION_SCRIPT, keys, argv);
    }

    /**
     * 운영 resync — {@code stock:{productId}} 를 product RDB 수량으로 단순 SET.
     * in-flight 선차감 덮어쓰기 주의: {@link StockCachePort#set} 주석 참고.
     */
    @Override
    public void set(Long productId, int quantity) {
        stockCacheRedisTemplate.opsForValue().set(stockKey(productId), String.valueOf(quantity));
    }

    /**
     * 주문 단위 확정 선점 획득 — {@code stock_order_lock_acquire.lua} 로 SETNX + EXPIRE 를 원자적으로 수행한다.
     * 선점 토큰은 매 호출마다 새로 발급해, 이 요청의 해제 시도가 다른 요청이 재획득한 선점을
     * 잘못 지우지 않게 한다.
     */
    @Override
    public Optional<String> acquireOrderLock(String orderId) {
        String lockToken = UUID.randomUUID().toString();
        List<String> keys = List.of(orderLockKey(orderId));
        String[] argv = {lockToken, String.valueOf(orderLockTtlSeconds)};
        String luaResult = stockCacheRedisTemplate.execute(ORDER_LOCK_ACQUIRE_SCRIPT, keys, argv);
        return "OK".equals(luaResult) ? Optional.of(lockToken) : Optional.empty();
    }

    /**
     * 주문 단위 확정 선점 해제 — {@code stock_order_lock_release.lua} 로 토큰이 일치할 때만 지운다.
     */
    @Override
    public void releaseOrderLock(String orderId, String lockToken) {
        List<String> keys = List.of(orderLockKey(orderId));
        String[] argv = {lockToken};
        stockCacheRedisTemplate.execute(ORDER_LOCK_RELEASE_SCRIPT, keys, argv);
    }

    private String stockKey(Long productId) {
        return KEY_PREFIX + productId + KEY_SUFFIX;
    }

    private String orderLockKey(String orderId) {
        return ORDER_LOCK_KEY_PREFIX + orderId;
    }

    private String decrementDoneKey(Long productId, String orderId) {
        return DEDUP_DECREMENT_PREFIX + productId + KEY_SUFFIX + ":" + orderId;
    }

    private String compensationDoneKey(Long productId, String orderId) {
        return DEDUP_COMPENSATION_PREFIX + productId + KEY_SUFFIX + ":" + orderId;
    }

}
