package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StockCachePort Fake — Redis 없이 application 계층 테스트용.
 * <p>
 * Thread-safe: ConcurrentHashMap + synchronized decrement/rollback.
 */
public class FakeStockCachePort implements StockCachePort {

    private final ConcurrentHashMap<Long, Integer> stock = new ConcurrentHashMap<>();

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
    }
}
