package com.hyoguoo.paymentplatform.product.infrastructure.messaging;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * product-service Kafka 토픽 상수.
 * ADR-12: 토픽 이름은 이 클래스에서만 정의한다.
 * ADR-30(공통 jar 금지): 다른 서비스의 Topics 클래스를 참조하지 않고 독립 선언.
 *
 * <p>토픽 네이밍 규약: {@code <source-service>.<type>.<action>[.modifier]}
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProductTopics {

    /**
     * 전 상품 재고 스냅샷 발행 토픽.
     * payment-service T1-17 warmup consumer가 구독 — Phase 1.17 pair.
     */
    public static final String EVENTS_STOCK_SNAPSHOT = "product.events.stock-snapshot";

    // 향후 Phase 3 진행 중 확장 예정:
    // public static final String STOCK_EVENTS_RESTORE = "stock.events.restore";
}
