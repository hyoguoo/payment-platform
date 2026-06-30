package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.payment.application.port.out.ProductPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.presentation.port.StockAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 재고 캐시(redis-stock)를 product RDB(SoT) 기준으로 재정렬하는 운영 resync use-case.
 *
 * <p>redis-stock 은 payment 의 선차감 캐시이고 product RDB 가 SoT 다. 부팅 직후 시드 스크립트가
 * 1회 정렬한 뒤로는 동기화 경로가 없어, product RDB 가 외부(입고/관리자)에서 변경되면 발산한다.
 * 이 use-case 는 발산이 확인된 productId 의 캐시 값을 product RDB 값으로 덮어써 보정한다.
 *
 * <p>in-flight 선차감 안전성은 {@link StockCachePort#set} 주석 참고 — 단순 덮어쓰기라
 * 트래픽이 조용한 시점/특정 productId 한정 호출이 전제다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockResyncUseCase implements StockAdminService {

    private final ProductPort productPort;
    private final StockCachePort stockCachePort;

    @Override
    public int resyncStockCache(Long productId) {
        ProductInfo productInfo = productPort.getProductInfoById(productId);
        int rdbStock = productInfo.getStock();
        stockCachePort.set(productId, rdbStock);
        LogFmt.warn(log, LogDomain.PRODUCT, EventType.STOCK_CACHE_RESYNC, () ->
                String.format("Stock cache resynced from RDB(SoT) - productId=%d, quantity=%d",
                        productId, rdbStock));
        return rdbStock;
    }
}
