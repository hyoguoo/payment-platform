package com.hyoguoo.paymentplatform.pg.exception;

/**
 * 벤더 응답이 중복 승인(Toss ALREADY_PROCESSED_PAYMENT / NicePay 2201)에 해당해
 * {@link com.hyoguoo.paymentplatform.pg.application.service.DuplicateApprovalHandler}가
 * 이미 pg_inbox·pg_outbox 상태 전이를 완료했음을 나타내는 센티넬 예외.
 *
 * <p>PgVendorCallService는 이를 별도 outcome(HANDLED_INTERNALLY)으로 받아
 * 자신의 handleSuccess·handleDefinitiveFailure 경로에서 중복 기록을 막는다.
 */
public class PgGatewayDuplicateHandledException extends RuntimeException {

    private PgGatewayDuplicateHandledException(String message) {
        super(message);
    }

    public static PgGatewayDuplicateHandledException of(String message) {
        return new PgGatewayDuplicateHandledException(message);
    }
}
