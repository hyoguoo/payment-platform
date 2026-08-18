package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockDecrementAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRecoveryCompensationResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StockCachePort Fake — Redis 없이 application 계층 테스트용.
 * <p>
 * Thread-safe: ConcurrentHashMap + synchronized atomic decrement/compensate.
 */
public class FakeStockCachePort implements StockCachePort {

    private final ConcurrentHashMap<Long, Integer> stock = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> orderLocks = new ConcurrentHashMap<>();
    // 상품 단위 메서드 전용 — productId:orderId 로 키를 잡는다
    private final Set<String> productDecrementDoneTokens = ConcurrentHashMap.newKeySet();
    private final Set<String> productCompensationDoneTokens = ConcurrentHashMap.newKeySet();

    @Override
    public synchronized StockDecrementAtomicResult decrementAtomic(String orderId, PaymentOrder paymentOrder) {
        String token = productToken(paymentOrder.getProductId(), orderId);
        if (productDecrementDoneTokens.contains(token)) {
            return StockDecrementAtomicResult.ALREADY_DONE;
        }
        int current = stock.getOrDefault(paymentOrder.getProductId(), 0);
        if (current < paymentOrder.getQuantity()) {
            return StockDecrementAtomicResult.INSUFFICIENT;
        }
        stock.merge(paymentOrder.getProductId(), -paymentOrder.getQuantity(), Integer::sum);
        productDecrementDoneTokens.add(token);
        return StockDecrementAtomicResult.OK;
    }

    @Override
    public synchronized StockRecoveryCompensationResult compensateIfDecremented(
            String orderId, PaymentOrder paymentOrder) {
        String token = productToken(paymentOrder.getProductId(), orderId);
        if (!productDecrementDoneTokens.contains(token)) {
            return StockRecoveryCompensationResult.NO_DECREMENT;
        }
        if (productCompensationDoneTokens.contains(token)) {
            return StockRecoveryCompensationResult.ALREADY_DONE;
        }
        stock.merge(paymentOrder.getProductId(), paymentOrder.getQuantity(), Integer::sum);
        productCompensationDoneTokens.add(token);
        return StockRecoveryCompensationResult.OK;
    }

    @Override
    public synchronized void rejectCompensate(String orderId, PaymentOrder paymentOrder) {
        stock.merge(paymentOrder.getProductId(), paymentOrder.getQuantity(), Integer::sum);
        String token = productToken(paymentOrder.getProductId(), orderId);
        productDecrementDoneTokens.remove(token);
        productCompensationDoneTokens.remove(token);
    }

    private String productToken(Long productId, String orderId) {
        return productId + ":" + orderId;
    }

    @Override
    public synchronized Optional<String> acquireOrderLock(String orderId) {
        if (orderLocks.containsKey(orderId)) {
            return Optional.empty();
        }
        String lockToken = UUID.randomUUID().toString();
        orderLocks.put(orderId, lockToken);
        return Optional.of(lockToken);
    }

    @Override
    public synchronized void releaseOrderLock(String orderId, String lockToken) {
        orderLocks.computeIfPresent(orderId, (key, heldToken) -> heldToken.equals(lockToken) ? null : heldToken);
    }

    // --- fixture 셋업 / 단언 헬퍼 (StockCachePort 계약 외, atomic 메서드 테스트 보조용) ---

    public int current(Long productId) {
        return stock.getOrDefault(productId, 0);
    }

    public void set(Long productId, int quantity) {
        stock.put(productId, quantity);
    }

    public Map<Long, Integer> getInternalMap() {
        return Collections.unmodifiableMap(stock);
    }

    /**
     * 선차감 dedup 표시(decrement:done)만 지운다 — 재고 값은 그대로 둔 채 TTL 만료를 흉내낸다.
     * 이 상태에서 {@link #decrementAtomic(String, PaymentOrder)} 를 다시 부르면 OK 를 돌려준다.
     */
    public void expireDecrementToken(Long productId, String orderId) {
        productDecrementDoneTokens.remove(productToken(productId, orderId));
    }

    public void clear() {
        stock.clear();
        orderLocks.clear();
        productDecrementDoneTokens.clear();
        productCompensationDoneTokens.clear();
    }
}
