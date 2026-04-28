package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.publisher;

import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentConfirmDlqPublisher;
import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * PaymentConfirmDlqPublisher Kafka 구현체 — dedupe remove 가 실패한 경우
 * payment.events.confirmed.dlq 토픽으로 DLQ 이벤트를 전송한다.
 *
 * <p>payload: "eventUuid={uuid} reason={reason}" 문자열.
 * key: eventUuid (파티션 일관성 보장).
 *
 * <p>ConditionalOnProperty: spring.kafka.bootstrap-servers 설정 환경에서만 활성화.
 * 테스트에서는 FakePaymentConfirmDlqPublisher를 사용한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class PaymentConfirmDlqKafkaPublisher implements PaymentConfirmDlqPublisher {

    private final KafkaTemplate<String, String> confirmedDlqKafkaTemplate;
    private final long sendTimeoutMillis;

    public PaymentConfirmDlqKafkaPublisher(
            @Qualifier("confirmedDlqKafkaTemplate")
            KafkaTemplate<String, String> confirmedDlqKafkaTemplate,
            @Value("${kafka.publisher.send-timeout-millis:10000}") long sendTimeoutMillis) {
        this.confirmedDlqKafkaTemplate = confirmedDlqKafkaTemplate;
        this.sendTimeoutMillis = sendTimeoutMillis;
    }

    @Override
    public void publishDlq(String eventUuid, String reason) {
        String payload = "eventUuid=" + eventUuid + " reason=" + reason;
        try {
            confirmedDlqKafkaTemplate
                    .send(PaymentTopics.EVENTS_CONFIRMED_DLQ, eventUuid, payload)
                    .get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
            LogFmt.info(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_SUCCESS,
                    () -> "topic=" + PaymentTopics.EVENTS_CONFIRMED_DLQ + " key=" + eventUuid);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LogFmt.error(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_FAIL,
                    () -> "DLQ publish interrupted eventUuid=" + eventUuid);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LogFmt.error(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_FAIL,
                    () -> "DLQ publish failed eventUuid=" + eventUuid + " error=" + cause.getMessage());
        } catch (TimeoutException e) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_FAIL,
                    () -> "DLQ publish timeout eventUuid=" + eventUuid
                            + " timeoutMs=" + sendTimeoutMillis);
        }
    }
}
