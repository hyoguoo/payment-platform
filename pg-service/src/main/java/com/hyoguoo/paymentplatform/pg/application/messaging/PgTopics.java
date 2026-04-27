package com.hyoguoo.paymentplatform.pg.application.messaging;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * pg-service Kafka 토픽 상수.
 * 토픽 이름은 이 클래스에서만 정의한다 — payment-service 의 PaymentTopics 를 참조하지 않고 독립 선언한다
 * (공통 jar 금지 정책).
 *
 * <p>application 계층이 infrastructure 패키지를 직접 참조하지 않도록 hexagonal layer 규약을 따른다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PgTopics {

    public static final String COMMANDS_CONFIRM = "payment.commands.confirm";
    public static final String COMMANDS_CONFIRM_DLQ = "payment.commands.confirm.dlq";
    public static final String EVENTS_CONFIRMED = "payment.events.confirmed";
}
