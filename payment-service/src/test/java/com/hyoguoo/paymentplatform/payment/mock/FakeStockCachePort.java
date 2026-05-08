package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCompensationAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockDecrementAtomicResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StockCachePort Fake — Redis 없이 application 계층 테스트용.
 * <p>
 * Thread-safe: ConcurrentHashMap + synchronized decrement/rollback.
 */
public class FakeStockCachePort implements StockCachePort {

    private final ConcurrentHashMap<Long, Integer> stock = new ConcurrentHashMap<>();
    private final Set<String> decrementDedupTokens = ConcurrentHashMap.newKeySet();
    private final Set<String> compensationDedupTokens = ConcurrentHashMap.newKeySet();

    @Override
    public synchronized StockDecrementAtomicResult decrementAtomic(
            String orderId, List<PaymentOrder> paymentOrders) {
        if (decrementDedupTokens.contains(orderId)) {
            return StockDecrementAtomicResult.ALREADY_DONE;
        }
        for (PaymentOrder order : paymentOrders) {
            int current = stock.getOrDefault(order.getProductId(), 0);
            if (current < order.getQuantity()) {
                return StockDecrementAtomicResult.INSUFFICIENT;
            }
        }
        for (PaymentOrder order : paymentOrders) {
            stock.merge(order.getProductId(), -order.getQuantity(), Integer::sum);
        }
        decrementDedupTokens.add(orderId);
        return StockDecrementAtomicResult.OK;
    }

    @Override
    public synchronized StockCompensationAtomicResult compensateAtomic(
            String orderId, List<PaymentOrder> paymentOrders) {
        if (compensationDedupTokens.contains(orderId)) {
            return StockCompensationAtomicResult.ALREADY_DONE;
        }
        for (PaymentOrder order : paymentOrders) {
            stock.merge(order.getProductId(), order.getQuantity(), Integer::sum);
        }
        compensationDedupTokens.add(orderId);
        return StockCompensationAtomicResult.OK;
    }

    @Override
    public synchronized boolean decrement(Long productId, int quantity) {
        int current = stock.getOrDefault(productId, 0);
        int after = current - quantity;
        if (after < 0) {
            return false;
        }
        stock.put(productId, after);
        return true;
    }

    @Override
    public synchronized void rollback(Long productId, int quantity) {
        stock.merge(productId, quantity, Integer::sum);
    }

    @Override
    public synchronized void increment(Long productId, int quantity) {
        stock.merge(productId, quantity, Integer::sum);
    }

    @Override
    public int current(Long productId) {
        return stock.getOrDefault(productId, 0);
    }

    @Override
    public Optional<Integer> findCurrent(Long productId) {
        return stock.containsKey(productId)
                ? Optional.of(stock.get(productId))
                : Optional.empty();
    }

    @Override
    public void set(Long productId, int quantity) {
        stock.put(productId, quantity);
    }

    // --- assertion helpers ---

    public Map<Long, Integer> getInternalMap() {
        return Collections.unmodifiableMap(stock);
    }

    public void clear() {
        stock.clear();
        decrementDedupTokens.clear();
        compensationDedupTokens.clear();
    }
}
