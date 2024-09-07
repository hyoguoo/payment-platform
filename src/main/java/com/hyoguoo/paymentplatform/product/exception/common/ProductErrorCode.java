package com.hyoguoo.paymentplatform.product.exception.common;

import com.hyoguoo.paymentplatform.core.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ProductErrorCode implements ErrorCode {

    NOT_NEGATIVE_NUMBER_TO_CALCULATE_STOCK("E01001", "재고를 계산할 때 음수를 사용할 수 없습니다."),
    NOT_ENOUGH_STOCK("E01002", "재고가 충분하지 않습니다."),
    PRODUCT_NOT_FOUND("E01003", "존재하지 않는 상품입니다."),
    PRICE_INVALID("E01004", "유효하지 않은 가격입니다."),
    ;

    private final String code;
    private final String message;
}
