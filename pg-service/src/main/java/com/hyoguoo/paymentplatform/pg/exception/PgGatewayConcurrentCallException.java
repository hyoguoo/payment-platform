package com.hyoguoo.paymentplatform.pg.exception;

/**
 * 벤더가 같은 멱등키로 아직 처리 중인 원 호출과 겹친 요청을 거부했음을 나타내는 센티넬 예외
 * (Toss IDEMPOTENT_REQUEST_PROCESSING, HTTP 409).
 *
 * <p>원 호출이 결과를 낼 예정이므로 {@link com.hyoguoo.paymentplatform.pg.application.service.PgVendorCallService}
 * 는 이를 별도 outcome(ConcurrentCall)으로 받아 시도횟수·재시도 명령·상태 전이를 건드리지 않고
 * 로그와 지표만 남긴 채 반환한다.
 */
public class PgGatewayConcurrentCallException extends RuntimeException {

    private PgGatewayConcurrentCallException(String message) {
        super(message);
    }

    public static PgGatewayConcurrentCallException of(String message) {
        return new PgGatewayConcurrentCallException(message);
    }
}
