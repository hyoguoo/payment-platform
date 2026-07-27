package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

/**
 * 재고 캐시를 읽지 못해 재고를 확인조차 못 한 상태.
 *
 * <p>재고가 실제로 모자란 것과는 다르다. 모자란 경우는 다시 시도해도 결과가 같지만,
 * 캐시 장애는 잠시 뒤 다시 시도하면 통과할 수 있다. 호출자가 두 상황을 구분해
 * 재시도 여부를 판단할 수 있도록 별도 예외로 신호한다.
 *
 * <p>{@link ProductServiceRetryableException} 과 같은 성격의 일시적 장애이므로 503 으로 응답한다.
 */
@Getter
public class StockCacheUnavailableException extends RuntimeException {

    private final String code;

    private StockCacheUnavailableException(PaymentErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public static StockCacheUnavailableException of(PaymentErrorCode errorCode) {
        return new StockCacheUnavailableException(errorCode);
    }
}
