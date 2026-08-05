package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.exception.PgVendorStatusQueryFailedException;
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
 * PgVendorStatusFeignClient 전용 Feign 설정.
 *
 * <p>{@link PgFeignConfig} 는 4xx/5xx 를 서로 다른 도메인 예외로 나누지만, 이 클라이언트는
 * {@code PgVendorStatusHttpAdapter} 가 모든 {@code RuntimeException} 을 확인 불가로 접어
 * 반환하므로 상태 코드별로 예외 타입을 나눌 필요가 없다 — 어떤 상태 코드든
 * {@link PgVendorStatusQueryFailedException} 하나로 통일한다.
 *
 * <p>NOTE: {@code @Configuration} 어노테이션 부착 금지. {@code @FeignClient(configuration=...)}
 * 로 한정 등록되어야 하며, 전역 {@code @Configuration} 으로 등록되면 다른 FeignClient 에도
 * 영향을 미친다(PgFeignConfig 와 같은 이유).
 */
public class PgVendorStatusFeignConfig {

    private static final Logger log = LoggerFactory.getLogger(PgVendorStatusFeignConfig.class);

    @Bean
    public ErrorDecoder pgVendorStatusErrorDecoder() {
        return (methodKey, response) -> mapToException(response);
    }

    private RuntimeException mapToException(Response response) {
        int status = response.status();
        String body = readBodyQuietly(response);

        LogFmt.warn(log, LogDomain.PAYMENT_GATEWAY, EventType.PG_VENDOR_STATUS_QUERY_INDETERMINATE,
                () -> "status=" + status + " body=" + body);
        return PgVendorStatusQueryFailedException.of(PaymentErrorCode.PG_VENDOR_STATUS_QUERY_UNAVAILABLE);
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
