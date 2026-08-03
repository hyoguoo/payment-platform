package com.hyoguoo.paymentplatform.payment.exception;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import lombok.Getter;

/**
 * 같은 주문의 확정 트랜잭션이 동시에 겹쳤을 때, 발행 행 삽입에서 진 쪽이 받는 예외.
 *
 * <p>제약 위반을 잡아 만드는 예외가 아니다 — 이미 있으면 조용히 넘어가는 삽입과 뒤이은
 * 잠금 읽기 확인으로 기존 행 존재를 확인한 뒤에만 던진다. 이긴 쪽이 이미 정상 확정을
 * 진행하므로 재고 손실이 없고, 재고 미회수 경보 대상에서 제외한다.
 */
@Getter
public class PaymentOutboxDuplicateException extends RuntimeException {

    private final String code;

    private PaymentOutboxDuplicateException(PaymentErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public static PaymentOutboxDuplicateException of(PaymentErrorCode errorCode) {
        return new PaymentOutboxDuplicateException(errorCode);
    }
}
