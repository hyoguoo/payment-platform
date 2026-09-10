package com.hyoguoo.paymentplatform.payment.presentation.port;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.StockResyncResult;

public interface StockAdminService {

    /**
     * product RDB(SoT)와 발산한 재고 캐시(redis-stock)를 해당 productId 의 RDB 값으로 재정렬한다.
     *
     * <p>그 상품에 진행 중(잡음 상태) 선차감이 있으면 기본적으로 거부한다 — {@code force=true}면
     * 걸린 건수를 경고로 남기고 강제로 진행한다.
     *
     * @param productId 재정렬 대상 상품 ID
     * @param force     진행 중 선차감이 있어도 강제로 재정렬할지 여부
     * @return 재정렬 결과(수량 + 덮어쓰기 전후 겹침 판정)
     */
    StockResyncResult resyncStockCache(Long productId, boolean force);
}
