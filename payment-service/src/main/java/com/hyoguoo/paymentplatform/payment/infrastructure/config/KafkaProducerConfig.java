package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.application.dto.event.PaymentConfirmCommandMessage;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * payment-service Kafka 프로듀서 — 토픽별 타입드 KafkaTemplate 빈 등록.
 *
 * <p>각 토픽은 전용 타입드 템플릿을 갖는다. 이 구조는 다음을 강제한다:
 * <ul>
 *   <li>토픽 이름은 application.yml 의 payment.kafka.topics.* 프로퍼티에서만 관리된다.</li>
 *   <li>send() 호출부에서 토픽-페이로드 타입 불일치가 컴파일 타임에 걸러진다.</li>
 *   <li>setDefaultTopic() 으로 발행 코드에서 토픽 문자열 누락/오타 가능성이 제거된다.</li>
 * </ul>
 *
 * <p>T-J1: stock publishing은 StockOutboxKafkaPublisher (StockOutboxPublisherPort 구현체)로 분리되었다.
 * 이 config는 payment.commands.confirm / stock_outbox / dlq 템플릿만 관리한다.
 */
@Configuration
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${payment.kafka.topics.commands-confirm}")
    private String commandsConfirmTopic;

    /**
     * payment.commands.confirm 전용 템플릿.
     * 발행 주체: payment-service OutboxRelayService — pg-service 로 confirm 명령을 넘긴다.
     */
    @Bean
    public KafkaTemplate<String, PaymentConfirmCommandMessage> commandsConfirmKafkaTemplate(
            ProducerFactory<String, PaymentConfirmCommandMessage> producerFactory) {
        return buildObservedTemplate(producerFactory, commandsConfirmTopic);
    }

    /**
     * stock_outbox relay 전용 String KafkaTemplate.
     * T-J1: stock_outbox row의 pre-serialized JSON payload를 재직렬화 없이 직접 발행.
     * StringSerializer ProducerFactory 사용 — JsonSerializer 혼용 방지.
     * T-J2: ObservationRegistry 명시 wiring — 자체 생성 DefaultKafkaProducerFactory는 Boot
     * auto-config의 ObservationRegistry interceptor wire-in을 받지 못하므로
     * setObservationRegistry()로 직접 주입해 traceparent 전파 경로를 확보한다.
     */
    @Bean
    public KafkaTemplate<String, String> stockOutboxKafkaTemplate(
            ObservationRegistry observationRegistry) {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        );
        ProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        KafkaTemplate<String, String> template = new KafkaTemplate<>(factory);
        template.setObservationEnabled(true);
        template.setObservationRegistry(observationRegistry);
        return template;
    }

    /**
     * payment.events.confirmed.dlq 전용 String KafkaTemplate.
     * T-C3: dedupe remove 실패 시 DLQ 전송용. String 페이로드(reason)만 전송.
     * 별도 StringSerializer ProducerFactory 사용 — JsonSerializer 혼용 방지.
     * T-J2: ObservationRegistry 명시 wiring (stockOutboxKafkaTemplate과 동일 패턴, 일관성).
     */
    @Bean
    public KafkaTemplate<String, String> confirmedDlqKafkaTemplate(
            ObservationRegistry observationRegistry) {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        );
        ProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        KafkaTemplate<String, String> template = new KafkaTemplate<>(factory);
        template.setDefaultTopic(PaymentTopics.EVENTS_CONFIRMED_DLQ);
        template.setObservationEnabled(true);
        template.setObservationRegistry(observationRegistry);
        return template;
    }

    /**
     * 토픽별 타입드 KafkaTemplate 공통 빌더 — defaultTopic 고정 + observation 활성화(T3.5-13).
     */
    private static <T> KafkaTemplate<String, T> buildObservedTemplate(
            ProducerFactory<String, T> factory, String defaultTopic) {
        KafkaTemplate<String, T> template = new KafkaTemplate<>(factory);
        template.setDefaultTopic(defaultTopic);
        template.setObservationEnabled(true);
        return template;
    }
}
