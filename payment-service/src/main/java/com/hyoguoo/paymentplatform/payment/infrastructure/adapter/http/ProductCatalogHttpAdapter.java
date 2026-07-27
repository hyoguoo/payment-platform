package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.ProductCatalogEntryInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.ProductCatalogPageInfo;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductCatalogQueryPort;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.exception.ProductServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.ProductPageResponse;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.ProductResponse;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign.ProductFeignClient;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * product-service 상품 목록(관리자 재고 화면) 조회 HTTP 어댑터.
 * ProductCatalogQueryPort 구현체 — 결제 승인 경로가 쓰는 ProductHttpAdapter/ProductPort 와
 * 분리된 관리자 조회 전용 어댑터라 별도 활성화 프로파일 없이 항상 등록된다.
 * ProductFeignClient(및 ErrorDecoder) 는 ProductHttpAdapter 와 공유한다.
 *
 * <p>4xx/5xx → 도메인 예외 매핑은 {@link ProductFeignClient} 에 연결된 ErrorDecoder(ProductFeignConfig) 가
 * 담당한다. transport-level 예외(RetryableException) 는 이 클래스에서
 * ProductServiceRetryableException 으로 매핑한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductCatalogHttpAdapter implements ProductCatalogQueryPort {

    private final ProductFeignClient productFeignClient;

    @Override
    public ProductCatalogPageInfo getPage(int page, int size) {
        try {
            ProductPageResponse response = productFeignClient.getProducts(page, size);
            return toProductCatalogPageInfo(response);
        } catch (feign.RetryableException e) {
            LogFmt.warn(log, LogDomain.PRODUCT, EventType.PRODUCT_SERVICE_RETRYABLE,
                    () -> "transport=" + e.getMessage());
            throw ProductServiceRetryableException.of(PaymentErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
    }

    private static ProductCatalogPageInfo toProductCatalogPageInfo(ProductPageResponse response) {
        return ProductCatalogPageInfo.builder()
                .content(toEntryInfoList(response.content()))
                .page(response.page())
                .size(response.size())
                .totalElements(response.totalElements())
                .totalPages(response.totalPages())
                .build();
    }

    private static List<ProductCatalogEntryInfo> toEntryInfoList(List<ProductResponse> content) {
        return content.stream()
                .map(ProductCatalogHttpAdapter::toEntryInfo)
                .collect(Collectors.toList());
    }

    private static ProductCatalogEntryInfo toEntryInfo(ProductResponse response) {
        return ProductCatalogEntryInfo.builder()
                .id(response.id())
                .name(response.name())
                .price(response.price())
                .confirmedStock(response.stock())
                .sellerId(response.sellerId())
                .build();
    }
}
