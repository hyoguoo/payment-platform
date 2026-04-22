package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import com.hyoguoo.paymentplatform.core.common.infrastructure.http.HttpOperator;
import com.hyoguoo.paymentplatform.payment.application.dto.request.OrderedProductStockCommand;
import com.hyoguoo.paymentplatform.payment.application.port.out.ProductPort;
import com.hyoguoo.paymentplatform.payment.domain.dto.ProductInfo;
import com.hyoguoo.paymentplatform.payment.exception.ProductServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.product.exception.ProductFoundException;
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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
 * @CircuitBreaker는 이 클래스 내부 메서드에만 적용 — port 인터페이스 오염 금지(ADR-22).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "product.adapter.type", havingValue = "http")
@RequiredArgsConstructor
public class ProductHttpAdapter implements ProductPort {

    private static final String PRODUCTS_PATH = "/api/v1/products/";
    private static final String STOCK_DECREASE_PATH = "/api/v1/products/stock/decrease";
    private static final String STOCK_INCREASE_PATH = "/api/v1/products/stock/increase";

    private final HttpOperator httpOperator;

    @Value("${product-service.base-url:http://localhost:8083}")
    private String productServiceBaseUrl;

    @Override
    public ProductInfo getProductInfoById(Long productId) {
        return fetchProductById(productId);
    }

    @Override
    public void decreaseStockForOrders(List<OrderedProductStockCommand> orderedProductStockCommandList) {
        postStockCommand(STOCK_DECREASE_PATH, orderedProductStockCommandList);
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
            log.warn("product_service_not_found status=404 body={}", e.getResponseBodyAsString());
            return ProductFoundException.of(ProductErrorCode.PRODUCT_NOT_FOUND);
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE.value()
                || status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            log.warn("product_service_retryable status={}", status);
            return ProductServiceRetryableException.of(PaymentErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
        log.warn("product_service_unexpected status={} body={}", status, e.getResponseBodyAsString());
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

    /**
     * product-service GET /api/v1/products/{id} 응답 DTO.
     */
    public record ProductResponse(
            Long id,
            String name,
            BigDecimal price,
            Integer stock,
            Long sellerId
    ) {}

    /**
     * product-service POST stock 요청 item.
     */
    record StockCommandItem(Long productId, Integer stock) {}
}
