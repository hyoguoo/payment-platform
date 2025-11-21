package com.hyoguoo.paymentplatform.payment.exception.common;

import com.hyoguoo.paymentplatform.core.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {

    PAYMENT_EVENT_NOT_FOUND("E03001", "존재하지 않는 결제 이벤트입니다."),
    INVALID_STATUS_TO_EXECUTE("E03002", "결제 실행할 수 없는 상태입니다."),
    INVALID_TOTAL_AMOUNT("E03002", "유효하지 않은 총 주문 금액입니다."),
    INVALID_ORDER_ID("E03003", "유효하지 않은 주문 ID입니다."),
    INVALID_USER_ID("E03004", "유효하지 않은 사용자 ID입니다."),
    INVALID_PAYMENT_KEY("E03005", "유효하지 않은 결제 키입니다."),
    NOT_IN_PROGRESS_ORDER("E03008", "진행 중인 주문이 아닙니다."),
    TOSS_RETRYABLE_ERROR("EO3009", "Toss 결제에서 재시도 가능한 오류가 발생했습니다."),
    TOSS_NON_RETRYABLE_ERROR("E03010", "Toss 결제에서 재시도 불가능한 오류가 발생했습니다."),
    INVALID_STATUS_TO_FAIL("E03011", "결제 실패할 수 없는 상태입니다."),
    INVALID_STATUS_TO_SUCCESS("E03012", "결제 성공할 수 없는 상태입니다."),
    ORDERED_PRODUCT_STOCK_NOT_ENOUGH("E03013", "주문한 상품 중 재고가 부족합니다."),
    INVALID_STATUS_TO_RETRY("E03014", "결제 재시도할 수 없는 상태입니다."),
    RETRYABLE_VALIDATION_ERROR("E03015", "재시도 불가능한 상태입니다."),
    INVALID_STATUS_TO_UNKNOWN("E03016", "결제를 알 수 없는 상태로 변경할 수 없습니다."),
    INVALID_STATUS_TO_EXPIRE("E03017", "결제를 만료 상태로 변경할 수 없습니다."),
    INVALID_STATUS_TO_COMPLETE("E03018", "작업을 완료할 수 없는 상태입니다."),
    INVENTORY_JOB_NOT_FOUND("E03019", "존재하지 않는 재고 작업입니다."),
    PAYMENT_PROCESS_NOT_FOUND("E03020", "존재하지 않는 결제 프로세스입니다."),
    UNSUPPORTED_PAYMENT_GATEWAY("E03021", "지원하지 않는 결제 게이트웨이입니다."),
    ;

    private final String code;
    private final String message;
}
