package com.hyoguoo.paymentplatform.payment.presentation.port;

public interface StockAdminService {

    /**
     * product RDB(SoT)와 발산한 재고 캐시(redis-stock)를 해당 productId 의 RDB 값으로 재정렬한다.
     *
     * @param productId 재정렬 대상 상품 ID
     * @return 재정렬된 재고 수량(product RDB 값)
     */
    int resyncStockCache(Long productId);
}
