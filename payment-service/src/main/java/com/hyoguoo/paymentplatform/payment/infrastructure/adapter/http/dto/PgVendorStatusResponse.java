package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto;

import java.time.Instant;

/**
 * pg-service GET /api/v1/confirmations/{orderId}/vendor-status 응답 수신 DTO.
 * pg-service presentation.dto.PgVendorStatusResponse 와 필드 시그니처가 일치해야 한다(레코드 역직렬화).
 */
public record PgVendorStatusResponse(
        String orderId,
        String judgement,
        String vendorStatus,
        Instant queriedAt
) {}
