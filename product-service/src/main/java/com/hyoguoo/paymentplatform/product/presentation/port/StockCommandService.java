package com.hyoguoo.paymentplatform.product.presentation.port;

import com.hyoguoo.paymentplatform.product.application.dto.ProductStockCommand;
import java.util.List;

/**
 * 재고 증감 inbound 포트.
 * ProductController가 동기 재고 차감/복구(HTTP) 요청을 위임한다.
 * 비동기 StockCommit/StockRestore 컨슈머 경로와 독립적이다.
 */
public interface StockCommandService {

    void decreaseForOrders(List<ProductStockCommand> commands);

    void increaseForOrders(List<ProductStockCommand> commands);
}
