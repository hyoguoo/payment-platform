package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

/**
 * PgVendorStatusFeignClient 호출 중 pg-service 가 반환한 4xx/5xx 응답을 표현.
 *
 * <p>PgVendorStatusHttpAdapter 가 모든 RuntimeException 을 확인 불가로 접어 반환하므로 port
 * 인터페이스 밖으로 나가지 않는다. PgFeignConfig 와 달리 상태 코드별로 예외 타입을 나누지 않는
 * 이유도 같다 — 최종적으로 동일하게 흡수되는 예외를 세분화해봐야 호출부에서 구분할 곳이 없다.
 */
@Getter
public class PgVendorStatusQueryFailedException extends RuntimeException {

    private final String code;

    private PgVendorStatusQueryFailedException(PaymentErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public static PgVendorStatusQueryFailedException of(PaymentErrorCode errorCode) {
        return new PgVendorStatusQueryFailedException(errorCode);
    }
}
