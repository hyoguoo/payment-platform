package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.exception.ProductNotFoundException;
import com.hyoguoo.paymentplatform.payment.exception.ProductServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import feign.Response;
import feign.codec.ErrorDecoder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;

/**
 * ProductFeignClient 전용 Feign 설정.
 *
 * <p>ErrorDecoder 가 4xx/5xx → 도메인 예외 매핑.
 * ProductFeignClient 호출에서 발생하는 4xx/5xx 응답을 도메인 예외로 매핑한다.
 *
 * <ul>
 *   <li>404 → {@link ProductNotFoundException} (PRODUCT_NOT_FOUND)</li>
 *   <li>429 / 503 → {@link ProductServiceRetryableException} (PRODUCT_SERVICE_UNAVAILABLE)</li>
 *   <li>그 외 5xx → {@link IllegalStateException}</li>
 * </ul>
 *
 * <p>NOTE: {@code @Configuration} 어노테이션 부착 금지.
 * Feign 의 {@code @FeignClient(configuration=...)} 로 한정 등록되어야 하며,
 * 전역 {@code @Configuration} 으로 등록되면 다른 FeignClient 에도 영향을 미친다.
 */
public class ProductFeignConfig {

    private static final Logger log = LoggerFactory.getLogger(ProductFeignConfig.class);

    @Bean
    public ErrorDecoder productErrorDecoder() {
        return (methodKey, response) -> mapToException(response);
    }

    private RuntimeException mapToException(Response response) {
        int status = response.status();
        String body = readBodyQuietly(response);

        if (status == HttpStatus.NOT_FOUND.value()) {
            LogFmt.warn(log, LogDomain.PRODUCT, EventType.PRODUCT_SERVICE_NOT_FOUND,
                    () -> "status=" + status + " body=" + body);
            return ProductNotFoundException.of(PaymentErrorCode.PRODUCT_NOT_FOUND);
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()
                || status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            LogFmt.warn(log, LogDomain.PRODUCT, EventType.PRODUCT_SERVICE_RETRYABLE,
                    () -> "status=" + status);
            return ProductServiceRetryableException.of(PaymentErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
        LogFmt.warn(log, LogDomain.PRODUCT, EventType.PRODUCT_SERVICE_UNEXPECTED,
                () -> "status=" + status + " body=" + body);
        return new IllegalStateException(
                "product-service 오류 status=" + status + " body=" + body
        );
    }

    private String readBodyQuietly(Response response) {
        if (response.body() == null) {
            return "";
        }
        return readBodyAsString(response.body());
    }

    private String readBodyAsString(Response.Body body) {
        try (InputStream inputStream = body.asInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(body read failed)";
        }
    }
}
