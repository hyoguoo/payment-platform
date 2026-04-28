package com.hyoguoo.paymentplatform.product.presentation.port;

import com.hyoguoo.paymentplatform.product.application.dto.ProductStockCommand;
import java.util.List;

/**
 * 재고 차감 inbound 포트.
 * ProductController 가 동기 재고 차감(HTTP) 요청을 위임한다.
 * 비동기 StockCommit 컨슈머 경로와 독립적이다.
 */
public interface StockCommandService {

    void decreaseForOrders(List<ProductStockCommand> commands);
}
