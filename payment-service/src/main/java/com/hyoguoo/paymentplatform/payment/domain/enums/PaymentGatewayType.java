package com.hyoguoo.paymentplatform.payment.domain.enums;

/**
 * DB 컬럼 바인딩 전용 열거형 (payment_event.gateway_type).
 * Phase 2 cutover(T2c-02, 2026-04-21) 이후 payment-service에 벤더 직접 호출 경로 없음.
 * 벤더 호출은 pg-service 책임 (ADR-02, ADR-21). pg-service는 PgVendorType을 독립 선언.
 */
public enum PaymentGatewayType {
    TOSS,
    NICEPAY
}
