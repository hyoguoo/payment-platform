package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.ProductCatalogLookupResult;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.ProductCatalogPageInfo;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductCatalogQueryPort;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.presentation.port.StockCatalogViewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 재고 화면(관리자) 상품 목록 조회 — 조회 실패 흡수 + 조회 불가 폴백 판단을 담당하는
 * presentation 입력 포트 구현.
 *
 * <p>{@code StockViewController}는 이 서비스가 반환하는 {@link ProductCatalogLookupResult}
 * 를 모델에 담는 일만 하고, 조회 실패 여부 판단은 이 서비스가 전담한다.
 *
 * <p>try 범위는 {@link ProductCatalogQueryPort#getPage} 호출 자체로 좁힌다 — 뷰 변환은
 * 이 서비스가 하지 않으므로, product-service 조회 실패와 payment-service 자체 매핑 버그가
 * 로그에서 섞이지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockCatalogViewServiceImpl implements StockCatalogViewService {

    private final ProductCatalogQueryPort productCatalogQueryPort;

    @Override
    public ProductCatalogLookupResult getPage(int page, int size) {
        ProductCatalogPageInfo pageInfo;
        try {
            pageInfo = productCatalogQueryPort.getPage(page, size);
        } catch (RuntimeException e) {
            LogFmt.warn(log, LogDomain.PRODUCT, EventType.PRODUCT_SERVICE_UNEXPECTED,
                    () -> "page=" + page + " size=" + size
                            + " errorType=" + e.getClass().getSimpleName()
                            + " error=" + e.getMessage());
            return ProductCatalogLookupResult.unavailable();
        }
        return ProductCatalogLookupResult.available(pageInfo);
    }
}
