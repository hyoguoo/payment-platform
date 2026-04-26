package com.hyoguoo.paymentplatform.product.mock;

import com.hyoguoo.paymentplatform.product.application.port.out.PaymentStockCachePort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PaymentStockCachePort Fake — in-memory 구현체 (product-service 테스트 전용).
 * <p>
 * StockCommitUseCase 단위 테스트에서 SET 호출 횟수·순서를 assert 하기 위한 in-memory fake.
 * <p>
 * 제공 기능:
 * <ul>
 *   <li>최종 상태: {@code ConcurrentHashMap<Long, Integer>} — 상품별 최신 재고</li>
 *   <li>SET 이력: {@code List<SetRecord>} — 호출 순서·인자 검증용</li>
 *   <li>호출 횟수: {@code AtomicInteger setCallCount}</li>
 * </ul>
 * Thread-safe.
 */
public class FakePaymentStockCachePort implements PaymentStockCachePort {

    /**
     * setStock 한 번 호출의 이력 레코드.
     */
    public record SetRecord(long productId, int stock, Instant timestamp) {

    }

    private final ConcurrentHashMap<Long, Integer> latestStock = new ConcurrentHashMap<>();
    private final List<SetRecord> history = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger setCallCount = new AtomicInteger(0);

    @Override
    public void setStock(long productId, int stock) {
        latestStock.put(productId, stock);
        history.add(new SetRecord(productId, stock, Instant.now()));
        setCallCount.incrementAndGet();
    }

    // --- 검증 헬퍼 ---

    /**
     * 특정 상품의 최신 재고를 반환한다. 호출 기록이 없으면 -1.
     */
    public int getLatestStock(long productId) {
        return latestStock.getOrDefault(productId, -1);
    }

    /**
     * 전체 setStock 호출 이력을 반환한다 (불변 복사본).
     */
    public List<SetRecord> getHistory() {
        return List.copyOf(history);
    }

    /**
     * 전체 setStock 호출 횟수를 반환한다.
     */
    public int getSetCallCount() {
        return setCallCount.get();
    }

    /**
     * 특정 상품에 대한 setStock 호출 횟수를 반환한다.
     */
    public long getSetCallCountFor(long productId) {
        return history.stream().filter(r -> r.productId() == productId).count();
    }

    public void reset() {
        latestStock.clear();
        history.clear();
        setCallCount.set(0);
    }
}
