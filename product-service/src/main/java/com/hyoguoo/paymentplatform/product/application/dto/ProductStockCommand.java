package com.hyoguoo.paymentplatform.product.application.dto;

/**
 * 재고 증감 커맨드.
 * HTTP 재고 감소/증가 요청 payload가 이 record로 매핑된다.
 */
public record ProductStockCommand(Long productId, Integer stock) {

}
