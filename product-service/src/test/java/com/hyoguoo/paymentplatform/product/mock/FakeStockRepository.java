package com.hyoguoo.paymentplatform.product.mock;

import com.hyoguoo.paymentplatform.product.application.port.out.StockRepository;
import com.hyoguoo.paymentplatform.product.domain.Stock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StockRepository Fake — in-memory 구현체 (product-service 테스트 전용).
 * <p>
 * Thread-safe: ConcurrentHashMap.compute 로 원자적 increment/decrement 보장.
 * T3-03 신설 — T3-04 이후 StockCommitUseCase 단위 테스트에서 소비 예정.
 */
public class FakeStockRepository implements StockRepository {

    private final ConcurrentHashMap<Long, Integer> store = new ConcurrentHashMap<>();

    @Override
    public List<Stock> findAll() {
        List<Stock> result = new ArrayList<>();
        store.forEach((productId, quantity) ->
                result.add(Stock.allArgsBuilder()
                        .productId(productId)
                        .quantity(quantity)
                        .allArgsBuild())
        );
        return result;
    }

    @Override
    public Optional<Stock> findByProductId(Long productId) {
        return Optional.ofNullable(store.get(productId))
                .map(quantity -> Stock.allArgsBuilder()
                        .productId(productId)
                        .quantity(quantity)
                        .allArgsBuild());
    }

    @Override
    public Stock save(Stock stock) {
        store.put(stock.getProductId(), stock.getQuantity());
        return stock;
    }

    // --- 원자적 재고 변경 헬퍼 ---

    /**
     * 재고를 원자적으로 증가시킨다.
     *
     * @param productId 상품 식별자
     * @param delta     증가량 (양수)
     * @return 변경 후 재고
     */
    public int increment(long productId, int delta) {
        return store.compute(productId, (id, current) -> (current == null ? 0 : current) + delta);
    }

    /**
     * 재고를 원자적으로 감소시킨다. 음수가 되면 감소하지 않고 false 반환.
     *
     * @param productId 상품 식별자
     * @param delta     감소량 (양수)
     * @return 감소 성공 여부
     */
    public boolean decrement(long productId, int delta) {
        int[] result = {0};
        boolean[] success = {false};
        store.compute(productId, (id, current) -> {
            int cur = (current == null ? 0 : current);
            int after = cur - delta;
            if (after < 0) {
                result[0] = cur;
                success[0] = false;
                return cur;
            }
            result[0] = after;
            success[0] = true;
            return after;
        });
        return success[0];
    }

    // --- 검증 헬퍼 ---

    public int size() {
        return store.size();
    }

    public void clear() {
        store.clear();
    }
}
