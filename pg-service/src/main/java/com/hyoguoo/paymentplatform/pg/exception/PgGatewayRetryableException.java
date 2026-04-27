package com.hyoguoo.paymentplatform.pg.exception;

/**
 * pg-service 내부 PG 게이트웨이 재시도 가능 예외.
 * 벤더 호출 실패 중 일시적 오류(네트워크 / 5xx 일부) 시 throw → 백오프 후 재시도 경로로 분기된다.
 */
public class PgGatewayRetryableException extends RuntimeException {

    private PgGatewayRetryableException(String message) {
        super(message);
    }

    public static PgGatewayRetryableException of(String message) {
        return new PgGatewayRetryableException(message);
    }
}
