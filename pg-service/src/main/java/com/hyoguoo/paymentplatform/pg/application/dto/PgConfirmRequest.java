package com.hyoguoo.paymentplatform.pg.application.dto;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import java.math.BigDecimal;

/**
 * pg-service 내부 PG 승인 요청 DTO.
 * payment-service의 PaymentConfirmRequest 의존을 끊고 pg-service 독립 DTO로 선언.
 */
public record PgConfirmRequest(
        String orderId,
        String paymentKey,
        BigDecimal amount,
        PgVendorType vendorType
) {

}
