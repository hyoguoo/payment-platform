package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.format.DateTimeFormatter;

/**
 * Toss Payments API 승인/조회 응답의 pg-service 전용 slim 버전.
 * 포팅 시 전략이 실제로 읽는 필드(paymentKey/orderId/totalAmount/status/approvedAt/failure)만 유지.
 *
 * <p>공통 jar 금지 정책에 따라 pg-service 독립 복제본으로 보유한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPaymentApiResponse(
        String paymentKey,
        String orderId,
        double totalAmount,
        String status,
        String approvedAt,
        Failure failure
) {

    public static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Failure(String code, String message) {
    }
}
