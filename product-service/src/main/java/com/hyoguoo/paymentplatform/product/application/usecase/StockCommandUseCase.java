package com.hyoguoo.paymentplatform.product.application.usecase;

import com.hyoguoo.paymentplatform.product.application.dto.ProductStockCommand;
import com.hyoguoo.paymentplatform.product.application.port.out.StockRepository;
import com.hyoguoo.paymentplatform.product.domain.Stock;
import com.hyoguoo.paymentplatform.product.exception.ProductNotFoundException;
import com.hyoguoo.paymentplatform.product.exception.ProductStockException;
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode;
import com.hyoguoo.paymentplatform.product.presentation.port.StockCommandService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동기 재고 증감 유스케이스.
 * <p>
 * HTTP POST /api/v1/products/stock/{decrease,increase} 요청을 처리한다.
 * 비동기 StockCommitUseCase(이벤트 dedupe 포함)와 달리 단순 CRUD 경로다.
 * <p>
 * 불변식:
 * <ul>
 *   <li>decrease 시 음수 stock 방어 — NOT_ENOUGH_STOCK throw</li>
 *   <li>존재하지 않는 productId — PRODUCT_NOT_FOUND throw (부분 실패 시 트랜잭션 롤백)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockCommandUseCase implements StockCommandService {

    private final StockRepository stockRepository;

    @Override
    @Transactional
    public void decreaseForOrders(List<ProductStockCommand> commands) {
        for (ProductStockCommand command : commands) {
            Stock current = loadStock(command.productId());
            int newQuantity = current.getQuantity() - command.stock();
            if (newQuantity < 0) {
                throw ProductStockException.of(ProductErrorCode.NOT_ENOUGH_STOCK);
            }
            stockRepository.save(Stock.allArgsBuilder()
                    .productId(current.getProductId())
                    .quantity(newQuantity)
                    .allArgsBuild());
        }
    }

    private Stock loadStock(Long productId) {
        return stockRepository.findByProductId(productId)
                .orElseThrow(() -> ProductNotFoundException.of(ProductErrorCode.PRODUCT_NOT_FOUND));
    }
}
