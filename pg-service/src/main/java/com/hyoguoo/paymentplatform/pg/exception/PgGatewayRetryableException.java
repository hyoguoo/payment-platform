package com.hyoguoo.paymentplatform.pg.exception;

/**
 * pg-service 내부 PG 게이트웨이 재시도 가능 예외.
 * payment-service의 PaymentGatewayRetryableException 의존을 끊고 pg-service 독립 예외로 선언.
 */
public class PgGatewayRetryableException extends RuntimeException {

    private PgGatewayRetryableException(String message) {
        super(message);
    }

    public static PgGatewayRetryableException of(String message) {
        return new PgGatewayRetryableException(message);
    }
}
