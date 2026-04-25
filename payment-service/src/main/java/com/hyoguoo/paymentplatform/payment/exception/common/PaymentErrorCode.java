package com.hyoguoo.paymentplatform.payment.exception.common;

import com.hyoguoo.paymentplatform.core.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {

    PAYMENT_EVENT_NOT_FOUND("E03001", "존재하지 않는 결제 이벤트입니다."),
    INVALID_STATUS_TO_EXECUTE("E03002", "결제 실행할 수 없는 상태입니다."),
    INVALID_TOTAL_AMOUNT("E03006", "유효하지 않은 총 주문 금액입니다."),
    INVALID_ORDER_ID("E03003", "유효하지 않은 주문 ID입니다."),
    INVALID_USER_ID("E03004", "유효하지 않은 사용자 ID입니다."),
    INVALID_PAYMENT_KEY("E03005", "유효하지 않은 결제 키입니다."),
    INVALID_STATUS_TO_FAIL("E03011", "결제 실패할 수 없는 상태입니다."),
    INVALID_STATUS_TO_SUCCESS("E03012", "결제 성공할 수 없는 상태입니다."),
    ORDERED_PRODUCT_STOCK_NOT_ENOUGH("E03013", "주문한 상품 중 재고가 부족합니다."),
    INVALID_STATUS_TO_RETRY("E03014", "결제 재시도할 수 없는 상태입니다."),
    INVALID_STATUS_TO_EXPIRE("E03017", "결제를 만료 상태로 변경할 수 없습니다."),
    INVALID_STATUS_TO_IN_FLIGHT("E03022", "IN_FLIGHT 상태로 변경할 수 없는 상태입니다."),
    INVALID_STATUS_TO_DONE("E03024", "DONE 상태로 변경할 수 없는 상태입니다."),
    INVALID_STATUS_TO_FAILED("E03025", "FAILED 상태로 변경할 수 없는 상태입니다."),
    INVALID_STATUS_TO_QUARANTINE("E03026", "QUARANTINED 상태로 변경할 수 없는 상태입니다."),
    MISSING_APPROVED_AT("E03027", "승인 시각(approvedAt)이 누락되어 완료 처리할 수 없습니다."),
    AMOUNT_MISMATCH("E03029", "결제 금액 위변조 감지"),
    INVALID_STATUS_TO_RESET("E03030", "READY 상태로 복원할 수 없는 상태입니다."),
    PRODUCT_SERVICE_UNAVAILABLE("E03031", "product-service가 일시적으로 사용 불가능합니다. 잠시 후 다시 시도해주세요."),
    USER_SERVICE_UNAVAILABLE("E03032", "user-service가 일시적으로 사용 불가능합니다. 잠시 후 다시 시도해주세요."),
    PRODUCT_NOT_FOUND("E03033", "존재하지 않는 상품입니다."),
    USER_NOT_FOUND("E03034", "존재하지 않는 사용자입니다."),
    ;

    private final String code;
    private final String message;
}
