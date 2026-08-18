package com.hyoguoo.paymentplatform.payment.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordCandidate;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordSnapshot;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.StockHoldRecordStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StockHoldRecordRepository Fake — DB 없이 application 계층 테스트용.
 * <p>
 * Thread-safe: ConcurrentHashMap + synchronized 상태 전이. 확정 상태를 재오픈하지 않는 것,
 * 사이클 식별 값이 일치할 때만 되돌림으로 닫히는 것까지 실제 어댑터와 같은 시맨틱을 재현한다.
 */
public class FakeStockHoldRecordRepository implements StockHoldRecordRepository {

    private record Entry(String orderId, Long productId, Integer quantity, StockHoldRecordStatus status,
                          String cycleToken) {

    }

    private final ConcurrentHashMap<String, Entry> records = new ConcurrentHashMap<>();

    @Override
    public synchronized String openHold(String orderId, PaymentOrder paymentOrder) {
        String key = key(orderId, paymentOrder.getProductId());
        Entry existing = records.get(key);
        if (existing != null && existing.status() == StockHoldRecordStatus.COMMITTED) {
            return existing.cycleToken();
        }
        String cycleToken = UUID.randomUUID().toString();
        records.put(key, new Entry(orderId, paymentOrder.getProductId(), paymentOrder.getQuantity(),
                StockHoldRecordStatus.NOISE, cycleToken));
        return cycleToken;
    }

    @Override
    public Optional<StockHoldRecordSnapshot> findSnapshot(String orderId, PaymentOrder paymentOrder) {
        Entry entry = records.get(key(orderId, paymentOrder.getProductId()));
        return entry == null
                ? Optional.empty()
                : Optional.of(new StockHoldRecordSnapshot(entry.status(), entry.cycleToken()));
    }

    @Override
    public synchronized boolean closeAsReverted(String orderId, PaymentOrder paymentOrder, String cycleToken) {
        String key = key(orderId, paymentOrder.getProductId());
        Entry existing = records.get(key);
        if (existing == null
                || existing.status() == StockHoldRecordStatus.COMMITTED
                || !existing.cycleToken().equals(cycleToken)) {
            return false;
        }
        records.put(key, new Entry(existing.orderId(), existing.productId(), existing.quantity(),
                StockHoldRecordStatus.REVERTED, cycleToken));
        return true;
    }

    @Override
    public synchronized void commitAllByOrderId(String orderId) {
        String prefix = orderId + ":";
        records.replaceAll((key, entry) -> key.startsWith(prefix) && entry.status() == StockHoldRecordStatus.NOISE
                ? new Entry(entry.orderId(), entry.productId(), entry.quantity(), StockHoldRecordStatus.COMMITTED,
                        entry.cycleToken())
                : entry);
    }

    @Override
    public synchronized List<StockHoldRecordCandidate> findNoiseCandidates(int limit) {
        return records.values().stream()
                .filter(entry -> entry.status() == StockHoldRecordStatus.NOISE)
                .limit(limit)
                .map(entry -> new StockHoldRecordCandidate(
                        entry.orderId(), entry.productId(), entry.quantity(), entry.cycleToken()))
                .toList();
    }

    @Override
    public synchronized long countNoise() {
        return records.values().stream()
                .filter(entry -> entry.status() == StockHoldRecordStatus.NOISE)
                .count();
    }

    // --- fixture 단언 헬퍼 (StockHoldRecordRepository 계약 외, 테스트 보조용) ---

    public Optional<StockHoldRecordStatus> statusOf(String orderId, Long productId) {
        Entry entry = records.get(key(orderId, productId));
        return entry == null ? Optional.empty() : Optional.of(entry.status());
    }

    public void clear() {
        records.clear();
    }

    private String key(String orderId, Long productId) {
        return orderId + ":" + productId;
    }
}
