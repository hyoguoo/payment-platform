package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import com.hyoguoo.paymentplatform.payment.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderedProductStockCommand;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductPort;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.exception.ProductNotFoundException;
import com.hyoguoo.paymentplatform.payment.exception.ProductServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.ProductResponse;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.StockCommandItem;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * product-service HTTP 어댑터 (ADR-02).
 * ProductPort 구현체. product.adapter.type=http 프로파일 활성화 시 사용.
 * Resilience4j @CircuitBreaker는 이 클래스 내부 메서드에 Phase 4에서 설치 예정 — port 인터페이스 오염 금지(ADR-22).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "product.adapter.type", havingValue = "http")
public class ProductHttpAdapter implements ProductPort {

    private static final String PRODUCTS_PATH = "/api/v1/products/";
    private static final String STOCK_INCREASE_PATH = "/api/v1/products/stock/increase";

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

    @Override
    public void increaseStockForOrders(List<OrderedProductStockCommand> orderedProductStockCommandList) {
        postStockCommand(STOCK_INCREASE_PATH, orderedProductStockCommandList);
    }

    private ProductInfo fetchProductById(Long productId) {
        ProductResponse response = callGet(
                productServiceBaseUrl + PRODUCTS_PATH + productId,
                ProductResponse.class
        );
        return toProductInfo(response);
    }

    private void postStockCommand(String path, List<OrderedProductStockCommand> commands) {
        List<StockCommandItem> items = commands.stream()
                .map(c -> new StockCommandItem(c.getProductId(), c.getStock()))
                .toList();
        callPost(productServiceBaseUrl + path, items);
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

    private void callPost(String url, Object body) {
        try {
            httpOperator.requestPost(url, Map.of(), body, Void.class);
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
