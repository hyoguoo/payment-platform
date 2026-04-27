package com.hyoguoo.paymentplatform.pg.exception;

/**
 * pg-service 내부 PG 게이트웨이 재시도 불가 예외.
 * 벤더 호출 결과가 확정 실패(인증·검증·정책 거부)임을 나타내며, 재시도 없이 FAILED 로 종결된다.
 */
public class PgGatewayNonRetryableException extends RuntimeException {

    private PgGatewayNonRetryableException(String message) {
        super(message);
    }

    public static PgGatewayNonRetryableException of(String message) {
        return new PgGatewayNonRetryableException(message);
    }
}
