package com.hyoguoo.paymentplatform.product.domain;

import lombok.Builder;
import lombok.Getter;

/**
 * 재고 값 객체.
 * Product와 분리하여 재고 전용 조회/갱신 경로(StockRepository)에서 사용.
 * T3-01 신설 — stock-snapshot 발행 훅에서 상품별 재고를 이 도메인 객체로 표현.
 */
@Getter
@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")
public class Stock {

    private Long productId;
    private Integer quantity;
}
