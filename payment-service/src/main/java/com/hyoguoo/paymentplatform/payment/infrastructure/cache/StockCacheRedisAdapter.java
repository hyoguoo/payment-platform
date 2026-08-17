package com.hyoguoo.paymentplatform.payment.infrastructure.cache;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCompensationAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockDecrementAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRecoveryCompensationResult;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
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
 * Lua 스크립트는 상품 하나만 원자적으로 다루고, 결제 단위(여러 상품) 처리는 이 어댑터가
 * 상품별로 스크립트를 반복 호출해 조립한다.
 *
 * <p><b>다중 상품 브리지</b>: {@link #decrementAtomic} 은 옛 order-level 스크립트가 주던
 * "하나라도 부족하면 아무것도 안 줄어든다" 보장을 반복 층에서 재현한다 — 상품을 순서대로
 * 차감하다 부족을 만나면, 이번 호출에서 직접 차감에 성공한 상품만 거절 전용 되돌리기로
 * 되돌린 뒤 부족을 반환한다. 이미 처리됨(ALREADY_DONE)을 받은 상품은 이번 호출이 차감한
 * 것이 아니므로 되돌리기 대상에서 뺀다. 되돌리기 호출 자체가 예외를 던지면 삼키지 않고
 * 그대로 전파한다 — 상위 계층이 캐시 장애로 판단해 격리 등으로 흡수할지 정한다.
 *
 * <p><b>주문 단위 선점</b>: {@link #acquireOrderLock}/{@link #releaseOrderLock} 은 상품 키와
 * 별개인 {@code stock:order-lock:orderId} 키를 쓴다. 동시 중복 확정 요청이 상품별로 승자가
 * 갈리는 것을 막기 위해 상품 반복 앞에서 한 번 잡고 반복이 끝나면 명시적으로 푼다.
 *
 * <p>AOF(appendonly yes) 전제 하에 재시작 복원이 보장된다.
 * Redis 연결 실패 시 예외를 그대로 전파한다 — QUARANTINED 분기 결정은 상위 계층 책임.
 */
@Slf4j
@Component
public class StockCacheRedisAdapter implements StockCachePort {

    private static final String KEY_PREFIX = "stock:{";
    private static final String KEY_SUFFIX = "}";
    private static final String DEDUP_DECREMENT_PREFIX = "decrement:done:{";
    private static final String DEDUP_COMPENSATION_PREFIX = "compensation:done:{";
    private static final long DEDUP_TTL_SECONDS = 691200L; // P8D
    private static final String ORDER_LOCK_KEY_PREFIX = "stock:order-lock:";

    private static final DefaultRedisScript<String> DECREMENT_ATOMIC_SCRIPT;
    private static final DefaultRedisScript<String> COMPENSATION_ATOMIC_SCRIPT;
    private static final DefaultRedisScript<String> COMPENSATION_IF_DECREMENTED_SCRIPT;
    private static final DefaultRedisScript<String> REJECT_COMPENSATION_SCRIPT;
    private static final DefaultRedisScript<String> ORDER_LOCK_ACQUIRE_SCRIPT;
    private static final DefaultRedisScript<Long> ORDER_LOCK_RELEASE_SCRIPT;

    static {
        DECREMENT_ATOMIC_SCRIPT = new DefaultRedisScript<>();
        DECREMENT_ATOMIC_SCRIPT.setLocation(new ClassPathResource("lua/stock_decrement_atomic.lua"));
        DECREMENT_ATOMIC_SCRIPT.setResultType(String.class);

        COMPENSATION_ATOMIC_SCRIPT = new DefaultRedisScript<>();
        COMPENSATION_ATOMIC_SCRIPT.setLocation(new ClassPathResource("lua/stock_compensation_atomic.lua"));
        COMPENSATION_ATOMIC_SCRIPT.setResultType(String.class);

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
     * 결제 단위 atomic 선차감 — 상품별로 {@code stock_decrement_atomic.lua} 를 반복 호출해 조립한다.
     *
     * <p>부족을 만나면 이번 호출에서 직접 차감한 상품만 거절 전용 되돌리기로 되돌리고
     * {@link StockDecrementAtomicResult#INSUFFICIENT} 를 반환한다. 모든 상품이 이미 처리됨이면
     * {@link StockDecrementAtomicResult#ALREADY_DONE}, 하나라도 이번에 새로 차감됐으면
     * {@link StockDecrementAtomicResult#OK} 를 반환한다.
     * 인프라 장애 시 RuntimeException 그대로 전파.
     */
    @Override
    public StockDecrementAtomicResult decrementAtomic(String orderId, List<PaymentOrder> paymentOrders) {
        List<PaymentOrder> decrementedThisCall = new ArrayList<>();
        boolean anyDecrementedThisCall = false;
        for (PaymentOrder order : paymentOrders) {
            StockDecrementAtomicResult result = decrementAtomic(orderId, order);
            if (result == StockDecrementAtomicResult.INSUFFICIENT) {
                rejectDecrementedProducts(orderId, decrementedThisCall);
                return StockDecrementAtomicResult.INSUFFICIENT;
            }
            if (result == StockDecrementAtomicResult.OK) {
                decrementedThisCall.add(order);
                anyDecrementedThisCall = true;
            }
        }
        return anyDecrementedThisCall ? StockDecrementAtomicResult.OK : StockDecrementAtomicResult.ALREADY_DONE;
    }

    /**
     * 결제 단위 atomic 보상(복원) — 상품별로 {@code stock_compensation_atomic.lua} 를 반복 호출해 조립한다.
     *
     * <p>모든 상품이 이미 처리됨이면 {@link StockCompensationAtomicResult#ALREADY_DONE},
     * 하나라도 이번에 새로 복원됐으면 {@link StockCompensationAtomicResult#OK} 를 반환한다.
     * 인프라 장애 시 RuntimeException 그대로 전파.
     */
    @Override
    public StockCompensationAtomicResult compensateAtomic(String orderId, List<PaymentOrder> paymentOrders) {
        boolean anyCompensatedThisCall = false;
        for (PaymentOrder order : paymentOrders) {
            if (compensateSingleProduct(orderId, order) == StockCompensationAtomicResult.OK) {
                anyCompensatedThisCall = true;
            }
        }
        StockCompensationAtomicResult result = anyCompensatedThisCall
                ? StockCompensationAtomicResult.OK
                : StockCompensationAtomicResult.ALREADY_DONE;
        logCompensation(orderId, paymentOrders, result.name());
        return result;
    }

    /**
     * 격리(QUARANTINED) 복구 전용 조건부 보상 — 상품별로 {@code stock_compensation_if_decremented.lua}
     * 를 반복 호출해 조립한다. {@code decrement:done} 토큰이 있을 때만 복원한다.
     *
     * <p>하나라도 정상 복원됐으면 {@link StockRecoveryCompensationResult#OK}, 복원은 없고 이미
     * 처리됨만 있었으면 {@link StockRecoveryCompensationResult#ALREADY_DONE}, 모든 상품이
     * 선차감 흔적이 없었으면 {@link StockRecoveryCompensationResult#NO_DECREMENT} 를 반환한다.
     * 인프라 장애 시 RuntimeException 그대로 전파.
     */
    @Override
    public StockRecoveryCompensationResult compensateIfDecremented(
            String orderId, List<PaymentOrder> paymentOrders) {
        boolean anyOk = false;
        boolean anyAlreadyDone = false;
        for (PaymentOrder order : paymentOrders) {
            StockRecoveryCompensationResult result = compensateIfDecremented(orderId, order);
            if (result == StockRecoveryCompensationResult.OK) {
                anyOk = true;
            } else if (result == StockRecoveryCompensationResult.ALREADY_DONE) {
                anyAlreadyDone = true;
            }
        }
        StockRecoveryCompensationResult result;
        if (anyOk) {
            result = StockRecoveryCompensationResult.OK;
        } else if (anyAlreadyDone) {
            result = StockRecoveryCompensationResult.ALREADY_DONE;
        } else {
            result = StockRecoveryCompensationResult.NO_DECREMENT;
        }
        logCompensation(orderId, paymentOrders, result.name());
        return result;
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
     * 상품 하나에 대한 atomic 보상(복원).
     *
     * <p>KEYS = [compensation:done:{productId}:orderId, stock:{productId}]
     * ARGV  = [qty, 691200]
     */
    private StockCompensationAtomicResult compensateSingleProduct(String orderId, PaymentOrder order) {
        List<String> keys = List.of(
                compensationDoneKey(order.getProductId(), orderId),
                stockKey(order.getProductId())
        );
        String[] argv = {String.valueOf(order.getQuantity()), String.valueOf(DEDUP_TTL_SECONDS)};
        String luaResult = stockCacheRedisTemplate.execute(COMPENSATION_ATOMIC_SCRIPT, keys, argv);
        return StockCompensationAtomicResult.valueOf(luaResult);
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
     * 이번 호출에서 직접 차감에 성공한 상품들을 거절 전용 되돌리기로 되돌린다.
     */
    private void rejectDecrementedProducts(String orderId, List<PaymentOrder> decrementedThisCall) {
        for (PaymentOrder order : decrementedThisCall) {
            rejectCompensate(orderId, order);
        }
    }

    /**
     * 되돌린 재고를 로그로 남긴다.
     *
     * <p>차감 확정은 product RDB 에 기록이 남지만 되돌림은 캐시 안에서만 일어나, 나중에 무엇이 얼마나
     * 풀렸는지 확인할 방법이 없었다. 어느 주문이 어느 상품을 얼마나 되돌렸고 그 직후 재고가 얼마인지 남긴다.
     *
     * <p>재고 조회는 로그를 위한 별도 읽기이므로 되돌림 자체의 원자성과 무관하다.
     */
    private void logCompensation(String orderId, List<PaymentOrder> paymentOrders, String result) {
        StringBuilder detail = new StringBuilder();
        for (PaymentOrder order : paymentOrders) {
            if (detail.length() > 0) {
                detail.append(", ");
            }
            detail.append("productId=").append(order.getProductId())
                    .append(" qty=").append(order.getQuantity())
                    .append(" 복원후=").append(readStockForLog(order.getProductId()));
        }
        String message = "orderId=" + orderId + " result=" + result + " " + detail;
        LogFmt.info(log, LogDomain.PAYMENT, EventType.STOCK_COMPENSATION_DONE, () -> message);
    }

    private String readStockForLog(Long productId) {
        String value = stockCacheRedisTemplate.opsForValue().get(stockKey(productId));
        return value == null ? "-" : value;
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
