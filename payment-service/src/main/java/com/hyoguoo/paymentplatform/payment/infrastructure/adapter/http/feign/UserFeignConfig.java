package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.exception.UserNotFoundException;
import com.hyoguoo.paymentplatform.payment.exception.UserServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import feign.Response;
import feign.codec.ErrorDecoder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;

/**
 * UserFeignClient 전용 Feign 설정.
 *
 * <p>ErrorDecoder 가 4xx/5xx → 도메인 예외 매핑.
 * 매핑 룰은 기존 UserHttpAdapter (HttpOperatorImpl 기반) 의 예외 분기와 정확히 일치한다.
 *
 * <ul>
 *   <li>404 → {@link UserNotFoundException} (USER_NOT_FOUND)</li>
 *   <li>429 / 503 → {@link UserServiceRetryableException} (USER_SERVICE_UNAVAILABLE)</li>
 *   <li>그 외 5xx → {@link IllegalStateException}</li>
 * </ul>
 *
 * <p>NOTE: {@code @Configuration} 어노테이션 부착 금지.
 * Feign 의 {@code @FeignClient(configuration=...)} 로 한정 등록되어야 하며,
 * 전역 {@code @Configuration} 으로 등록되면 다른 FeignClient 에도 영향을 미친다.
 */
public class UserFeignConfig {

    private static final Logger log = LoggerFactory.getLogger(UserFeignConfig.class);

    @Bean
    public ErrorDecoder userErrorDecoder() {
        return (methodKey, response) -> mapToException(response);
    }

    private RuntimeException mapToException(Response response) {
        int status = response.status();
        String body = readBodyQuietly(response);

        if (status == 404) {
            LogFmt.warn(log, LogDomain.USER, EventType.USER_SERVICE_NOT_FOUND,
                    () -> "status=404 body=" + body);
            return UserNotFoundException.of(PaymentErrorCode.USER_NOT_FOUND);
        }
        if (status == 429 || status == 503) {
            LogFmt.warn(log, LogDomain.USER, EventType.USER_SERVICE_RETRYABLE,
                    () -> "status=" + status);
            return UserServiceRetryableException.of(PaymentErrorCode.USER_SERVICE_UNAVAILABLE);
        }
        LogFmt.warn(log, LogDomain.USER, EventType.USER_SERVICE_UNEXPECTED,
                () -> "status=" + status + " body=" + body);
        return new IllegalStateException(
                "user-service 오류 status=" + status + " body=" + body
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
