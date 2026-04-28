package com.hyoguoo.paymentplatform.payment.domain.enums;

/**
 * DB 컬럼 바인딩 전용 열거형 (payment_event.gateway_type).
 * payment-service 에 벤더 직접 호출 경로는 없으며, 실제 벤더 호출은 pg-service 가 전담한다.
 * pg-service 는 자체적으로 PgVendorType 을 독립 선언한다.
 */
public enum PaymentGatewayType {
    TOSS,
    NICEPAY
}
