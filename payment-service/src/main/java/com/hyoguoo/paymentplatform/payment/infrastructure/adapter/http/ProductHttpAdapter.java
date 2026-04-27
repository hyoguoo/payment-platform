package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import com.hyoguoo.paymentplatform.payment.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductPort;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.exception.ProductNotFoundException;
import com.hyoguoo.paymentplatform.payment.exception.ProductServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.ProductResponse;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * product-service HTTP 어댑터.
 * ProductPort 구현체로, {@code product.adapter.type=http} 프로파일에서 활성화된다.
 * Resilience4j {@code @CircuitBreaker} 는 Phase 4 에서 이 클래스 내부 메서드에 설치한다 — port 인터페이스는 회복성 어노테이션으로부터 격리된 채로 유지한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "product.adapter.type", havingValue = "http")
public class ProductHttpAdapter implements ProductPort {

    private static final String PRODUCTS_PATH = "/api/v1/products/";

    private final HttpOperator httpOperator;
    private final String productServiceBaseUrl;

    public ProductHttpAdapter(
            HttpOperator httpOperator,
            @Value("${product-service.base-url:http://localhost:8083}") String productServiceBaseUrl) {
        this.httpOperator = httpOperator;
        this.productServiceBaseUrl = productServiceBaseUrl;
    }

    @Override
    public ProductInfo getProductInfoById(Long productId) {
        return fetchProductById(productId);
    }

    private ProductInfo fetchProductById(Long productId) {
        ProductResponse response = callGet(
                productServiceBaseUrl + PRODUCTS_PATH + productId,
                ProductResponse.class
        );
        return toProductInfo(response);
    }

    private <T> T callGet(String url, Class<T> responseType) {
        try {
            return httpOperator.requestGet(url, Map.of(), responseType);
        } catch (WebClientResponseException e) {
            throw mapResponseException(e);
        } catch (WebClientRequestException e) {
            throw ProductServiceRetryableException.of(PaymentErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
    }

    private RuntimeException mapResponseException(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == HttpStatus.NOT_FOUND.value()) {
            LogFmt.warn(log, LogDomain.PRODUCT, EventType.PRODUCT_SERVICE_NOT_FOUND,
                    () -> "status=404 body=" + e.getResponseBodyAsString());
            return ProductNotFoundException.of(PaymentErrorCode.PRODUCT_NOT_FOUND);
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE.value()
                || status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            LogFmt.warn(log, LogDomain.PRODUCT, EventType.PRODUCT_SERVICE_RETRYABLE,
                    () -> "status=" + status);
            return ProductServiceRetryableException.of(PaymentErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
        LogFmt.warn(log, LogDomain.PRODUCT, EventType.PRODUCT_SERVICE_UNEXPECTED,
                () -> "status=" + status + " body=" + e.getResponseBodyAsString());
        return new IllegalStateException(
                "product-service 오류 status=" + status + " body=" + e.getResponseBodyAsString()
        );
    }

    private static ProductInfo toProductInfo(ProductResponse response) {
        return ProductInfo.builder()
                .id(response.id())
                .name(response.name())
                .price(response.price())
                .stock(response.stock())
                .sellerId(response.sellerId())
                .build();
    }

}
