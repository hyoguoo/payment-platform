package com.hyoguoo.paymentplatform.product.infrastructure.messaging;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * product-service Kafka 토픽 상수.
 * 토픽 이름은 이 클래스에서만 정의한다 — 공통 jar 금지 정책에 따라 다른 서비스의 Topics 를 참조하지 않는다.
 *
 * <p>토픽 네이밍 규약: {@code <source-service>.<type>.<action>[.modifier]}
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProductTopics {

    /**
     * payment-service 가 발행하는 재고 확정 커밋 이벤트 토픽.
     * product-service StockCommitConsumer 가 구독한다.
     * 발행 주체와 상관없이 consumer 측도 상수를 독립 선언한다 (공통 jar 금지).
     */
    public static final String PAYMENT_EVENTS_STOCK_COMMITTED = "payment.events.stock-committed";

    /**
     * {@link #PAYMENT_EVENTS_STOCK_COMMITTED} 소비 실패(재시도 소진 포함) 격리 토픽.
     * {@code KafkaErrorHandlerConfig} 의 recoverer 가 발행하고, 재고 확정 음수 가드
     * ({@code StockCommitUseCase.commitToRdb} 의 {@code ProductStockException})처럼 재시도로
     * 풀리지 않는 예외는 재시도 없이 곧바로 이 토픽으로 간다.
     */
    public static final String PAYMENT_EVENTS_STOCK_COMMITTED_DLQ = "payment.events.stock-committed.dlq";
}
