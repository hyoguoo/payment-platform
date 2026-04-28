package com.hyoguoo.paymentplatform.product.application.port.out;

import com.hyoguoo.paymentplatform.product.domain.Stock;
import java.util.Optional;

/**
 * 재고 outbound 포트.
 */
public interface StockRepository {

    Optional<Stock> findByProductId(Long productId);

    Stock save(Stock stock);
}
