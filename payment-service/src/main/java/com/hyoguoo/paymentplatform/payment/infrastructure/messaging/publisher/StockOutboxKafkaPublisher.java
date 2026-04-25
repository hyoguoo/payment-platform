package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.publisher;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockOutboxPublisherPort;
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
 * StockOutboxPublisherPort 구현체 — String payload Kafka 발행 어댑터.
 * T-J1: stock_outbox row의 pre-serialized JSON String을 직접 발행한다.
 *
 * <p>ADR-04: KafkaTemplate 직접 호출은 인프라 계층 publisher에서만 허용.
 * <p>stockOutboxKafkaTemplate(KafkaTemplate<String, String>)을 사용하므로
 * JSON 재직렬화 없이 outbox row의 payload를 그대로 발행한다.
 *
 * <p>ConditionalOnProperty: spring.kafka.bootstrap-servers 설정 시만 활성화.
 * 테스트에서는 FakeStockOutboxPublisher를 직접 주입해 Kafka 없이 검증한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class StockOutboxKafkaPublisher implements StockOutboxPublisherPort {

    private final KafkaTemplate<String, String> stockOutboxKafkaTemplate;
    /** K6: @Value 생성자 파라미터 주입 — 필드 final 부여. */
    private final long sendTimeoutMillis;

    public StockOutboxKafkaPublisher(
            @Qualifier("stockOutboxKafkaTemplate")
            KafkaTemplate<String, String> stockOutboxKafkaTemplate,
            @Value("${kafka.publisher.send-timeout-millis:10000}") long sendTimeoutMillis) {
        this.stockOutboxKafkaTemplate = stockOutboxKafkaTemplate;
        this.sendTimeoutMillis = sendTimeoutMillis;
    }

    @Override
    public void send(String topic, String key, String payload) {
        try {
            stockOutboxKafkaTemplate.send(topic, key, payload).get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
            LogFmt.debug(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_SUCCESS,
                    () -> "stockOutbox topic=" + topic + " key=" + key);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LogFmt.error(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_FAIL,
                    () -> "interrupted stockOutbox topic=" + topic + " key=" + key);
            throw new IllegalStateException("Kafka 발행 중단 topic=" + topic, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LogFmt.error(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_FAIL,
                    () -> "stockOutbox topic=" + topic + " key=" + key + " error=" + cause.getMessage());
            throw new IllegalStateException("Kafka 발행 실패 topic=" + topic, cause);
        } catch (TimeoutException e) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_FAIL,
                    () -> "timeout stockOutbox topic=" + topic + " timeoutMs=" + sendTimeoutMillis);
            throw new IllegalStateException("Kafka 발행 타임아웃 topic=" + topic, e);
        }
    }
}
