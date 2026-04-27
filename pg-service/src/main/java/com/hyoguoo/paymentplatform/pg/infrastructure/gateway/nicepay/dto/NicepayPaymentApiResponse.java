package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.nicepay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * NicePay REST API 응답. pg-service 전용 slim 버전.
 * 공통 jar 금지 정책에 따라 pg-service 독립 복제본으로 보유한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NicepayPaymentApiResponse(
        String resultCode,
        String resultMsg,
        String tid,
        String orderId,
        BigDecimal amount,
        String status,
        String paidAt
) {

    public static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
}
