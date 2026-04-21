package com.hyoguoo.paymentplatform.pg.domain.enums;

/**
 * pg-service 내부 벤더 구분 타입.
 * payment-service의 PaymentGatewayType과 동일한 값을 유지하되 독립 선언 (ADR-30 공통 jar 금지).
 */
public enum PgVendorType {
    TOSS,
    NICEPAY
}
