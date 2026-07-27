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
 * Lua 스크립트로 결제 단위 N개 상품 차감/복원과 dedup token SETNX 를 원자적으로 수행한다.
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

    private static final DefaultRedisScript<String> DECREMENT_ATOMIC_SCRIPT;
    private static final DefaultRedisScript<String> COMPENSATION_ATOMIC_SCRIPT;
    private static final DefaultRedisScript<String> COMPENSATION_IF_DECREMENTED_SCRIPT;

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
        StockCompensationAtomicResult result = StockCompensationAtomicResult.valueOf(luaResult);
        logCompensation(orderId, paymentOrders, result.name());
        return result;
    }

    /**
     * 격리(QUARANTINED) 복구 전용 조건부 보상.
     *
     * <p>KEYS = [decrement:done:{orderId}, compensation:done:{orderId}, stock:{prod1}, stock:{prod2}, ...]
     * ARGV  = [qty1, qty2, ..., 691200]
     * {@code decrement:done} 토큰이 없으면 보상 없이 {@link StockRecoveryCompensationResult#NO_DECREMENT} 반환.
     * Lua 결과 문자열 → {@link StockRecoveryCompensationResult} enum 변환.
     * 인프라 장애 시 RuntimeException 그대로 전파.
     */
    @Override
    public StockRecoveryCompensationResult compensateIfDecremented(
            String orderId, List<PaymentOrder> paymentOrders) {
        List<String> keys = buildRecoveryCompensationKeys(orderId, paymentOrders);
        String[] argv = buildArgv(paymentOrders);
        String luaResult = stockCacheRedisTemplate.execute(COMPENSATION_IF_DECREMENTED_SCRIPT, keys, argv);
        StockRecoveryCompensationResult result = StockRecoveryCompensationResult.valueOf(luaResult);
        logCompensation(orderId, paymentOrders, result.name());
        return result;
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
        String value = stockCacheRedisTemplate.opsForValue().get(KEY_PREFIX + productId);
        return value == null ? "-" : value;
    }

    /**
     * 운영 resync — {@code stock:{productId}} 를 product RDB 수량으로 단순 SET.
     * in-flight 선차감 덮어쓰기 주의: {@link StockCachePort#set} 주석 참고.
     */
    @Override
    public void set(Long productId, int quantity) {
        stockCacheRedisTemplate.opsForValue().set(KEY_PREFIX + productId, String.valueOf(quantity));
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

    private List<String> buildRecoveryCompensationKeys(String orderId, List<PaymentOrder> paymentOrders) {
        List<String> keys = new ArrayList<>();
        keys.add(DEDUP_DECREMENT_PREFIX + orderId);
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

}
