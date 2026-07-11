package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCompensationAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockDecrementAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRecoveryCompensationResult;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StockCachePort Fake — Redis 없이 application 계층 테스트용.
 * <p>
 * Thread-safe: ConcurrentHashMap + synchronized atomic decrement/compensate.
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
    public synchronized StockRecoveryCompensationResult compensateIfDecremented(
            String orderId, List<PaymentOrder> paymentOrders) {
        if (!decrementDedupTokens.contains(orderId)) {
            return StockRecoveryCompensationResult.NO_DECREMENT;
        }
        if (compensationDedupTokens.contains(orderId)) {
            return StockRecoveryCompensationResult.ALREADY_DONE;
        }
        for (PaymentOrder order : paymentOrders) {
            stock.merge(order.getProductId(), order.getQuantity(), Integer::sum);
        }
        compensationDedupTokens.add(orderId);
        return StockRecoveryCompensationResult.OK;
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

    public void clear() {
        stock.clear();
        decrementDedupTokens.clear();
        compensationDedupTokens.clear();
    }
}
