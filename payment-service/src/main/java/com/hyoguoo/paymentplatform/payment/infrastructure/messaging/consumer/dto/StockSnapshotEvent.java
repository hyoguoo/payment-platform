package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer.dto;

import java.time.Instant;

/**
 * product.events.stock-snapshot 토픽 payload DTO.
 *
 * <p>Phase-3.1(T3 계열)에서 product-service가 실제 snapshot을 발행할 때까지
 * 이 DTO는 consumer 어댑터와 warmup 서비스 간의 계약으로만 사용된다.
 *
 * @param productId  상품 ID
 * @param quantity   재고 수량
 * @param capturedAt snapshot 생성 시각
 */
public record StockSnapshotEvent(
        Long productId,
        int quantity,
        Instant capturedAt
) {

}
