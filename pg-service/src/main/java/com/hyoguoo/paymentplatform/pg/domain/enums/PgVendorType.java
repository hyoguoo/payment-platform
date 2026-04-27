package com.hyoguoo.paymentplatform.pg.domain.enums;

/**
 * pg-service 내부 벤더 구분 타입.
 * payment-service 의 PaymentGatewayType 과 동일한 값을 유지하되 공통 jar 를 두지 않고 독립 선언한다.
 */
public enum PgVendorType {
    TOSS,
    NICEPAY
}
