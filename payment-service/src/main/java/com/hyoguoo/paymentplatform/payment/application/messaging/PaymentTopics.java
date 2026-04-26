package com.hyoguoo.paymentplatform.payment.application.messaging;

import lombok.NoArgsConstructor;

/**
 * payment-service Kafka 토픽 상수.
 * ADR-12: 토픽 이름은 이 클래스에서만 정의한다. KafkaTopicConfig 등 모든 참조처는 이 상수를 사용한다.
 *
 * <p>K9a: infrastructure.messaging → application.messaging 으로 이동.
 * application 계층이 infrastructure 패키지를 직접 참조하지 않도록 hexagonal layer 규약 준수.
 */
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class PaymentTopics {

    public static final String COMMANDS_CONFIRM = "payment.commands.confirm";
    public static final String COMMANDS_CONFIRM_DLQ = "payment.commands.confirm.dlq";
    public static final String EVENTS_CONFIRMED = "payment.events.confirmed";
    public static final String EVENTS_CONFIRMED_DLQ = "payment.events.confirmed.dlq";
    public static final String EVENTS_STOCK_COMMITTED = "payment.events.stock-committed";
}
