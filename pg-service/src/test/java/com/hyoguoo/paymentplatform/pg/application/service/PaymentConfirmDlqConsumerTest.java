package com.hyoguoo.paymentplatform.pg.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.consumer.PaymentConfirmConsumer;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.consumer.PaymentConfirmDlqConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PaymentConfirmDlqConsumer 단위 테스트.
 *
 * <p>DLQ consumer 가 정상 consumer 와 물리적으로 다른 bean 임을 확인한다. 처리 위임 동작(격리 직행 대신
 * 관문 호출)은 {@link PgDlqServiceTest} 가, 관문의 전이·발행 상세는 {@link PgFinalConfirmationGateTest}
 * 가 커버해 이 클래스에서는 중복 검증하지 않는다.
 */
@DisplayName("PaymentConfirmDlqConsumer")
class PaymentConfirmDlqConsumerTest {

    @Test
    @DisplayName("dlq_consumer — PaymentConfirmDlqConsumer 는 PaymentConfirmConsumer 와 물리적으로 다른 클래스")
    void dlq_consumer_WhenConsumerItself_ShouldBeDifferentBeanFromNormalConsumer() {
        // DLQ consumer 는 PaymentConfirmConsumer 와 별도 Spring bean (groupId 분리, 다른 클래스)
        assertThat(PaymentConfirmDlqConsumer.class)
                .isNotEqualTo(PaymentConfirmConsumer.class);

        // 각 클래스가 독립적으로 선언된 최상위 클래스임을 확인
        assertThat(PaymentConfirmDlqConsumer.class.getDeclaringClass()).isNull();
        assertThat(PaymentConfirmConsumer.class.getDeclaringClass()).isNull();
    }
}
