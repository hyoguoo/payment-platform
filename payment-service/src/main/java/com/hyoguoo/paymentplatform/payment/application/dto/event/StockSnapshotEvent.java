package com.hyoguoo.paymentplatform.payment.application.dto.event;

import java.time.Instant;

/**
 * product.events.stock-snapshot 토픽 payload DTO.
 *
 * <p>Phase-3.1(T3 계열)에서 product-service가 실제 snapshot을 발행할 때까지
 * 이 DTO는 consumer 어댑터와 warmup 서비스 간의 계약으로만 사용된다.
 *
 * <p>K9b: infrastructure.messaging.consumer.dto → application.dto.event 으로 이동.
 * application 계층이 infrastructure 패키지를 직접 참조하지 않도록 hexagonal layer 규약 준수.
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
