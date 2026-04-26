package com.hyoguoo.paymentplatform.product.application.port.out;

import com.hyoguoo.paymentplatform.product.domain.Stock;
import java.util.List;
import java.util.Optional;

/**
 * 재고 outbound 포트.
 */
public interface StockRepository {

    /**
     * 모든 상품의 재고 스냅샷을 반환한다.
     * StockSnapshotPublisher(ApplicationReadyEvent)에서 warmup 발행 용도로 사용.
     */
    List<Stock> findAll();

    Optional<Stock> findByProductId(Long productId);

    Stock save(Stock stock);
}
