package com.hyoguoo.paymentplatform.pg.application.messaging;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * pg-service Kafka 토픽 상수.
 * ADR-12: 토픽 이름은 이 클래스에서만 정의한다.
 * ADR-30(공통 jar 금지): payment-service의 PaymentTopics를 참조하지 않고 독립 선언.
 *
 * <p>K9a: infrastructure.messaging → application.messaging 으로 이동.
 * application 계층이 infrastructure 패키지를 직접 참조하지 않도록 hexagonal layer 규약 준수.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PgTopics {

    public static final String COMMANDS_CONFIRM = "payment.commands.confirm";
    public static final String COMMANDS_CONFIRM_DLQ = "payment.commands.confirm.dlq";
    public static final String EVENTS_CONFIRMED = "payment.events.confirmed";
}
