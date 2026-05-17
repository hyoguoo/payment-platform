package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.application.dto.event.PaymentConfirmCommandMessage;
import io.micrometer.observation.ObservationRegistry;
import java.util.HashMap;
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
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.transaction.KafkaTransactionManager;

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
 * <p>stock-committed 발행은 EOS-aware ProducerFactory (stockCommittedProducerFactory) 를 사용한다.
 * transactional.id = ${spring.application.name}-${HOSTNAME:local} (D4 결정 — 단일 인스턴스 가정).
 * 이 config 는 payment.commands.confirm / stock-committed (EOS) / confirmed.dlq 템플릿을 관리한다.
 * stockCommittedKafkaTemplate 이 EOS 발행 전용 빈.
 */
@Configuration
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${payment.kafka.topics.commands-confirm}")
    private String commandsConfirmTopic;

    @Value("${payment.kafka.topics.events-stock-committed}")
    private String eventsStockCommittedTopic;

    /**
     * D4 결정 — transactional.id prefix.
     * ${spring.application.name}-${HOSTNAME:local} 패턴으로 단일 인스턴스 가정.
     * 다중 인스턴스 확장 시 CONCERNS.md L6 / TODOS TC-13-FOLLOW-1 참조.
     */
    @Value("${payment.kafka.transactional-id-prefix:${spring.application.name}-${HOSTNAME:local}}")
    private String transactionalIdPrefix;

    /**
     * EOS-aware ProducerFactory — stock-committed 발행 전용.
     * transactional.id prefix + enable.idempotence=true + transaction.timeout.ms=10000 (D4).
     * transaction.timeout.ms = 10000 — RDB @Transactional(timeout=5) 의 2배 마진.
     */
    @Bean
    public ProducerFactory<String, String> stockCommittedProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, 10000);
        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        factory.setTransactionIdPrefix(transactionalIdPrefix + "-");
        return factory;
    }

    /**
     * KafkaTransactionManager — EOS stock-committed 발행 전용.
     * PET-7 에서 kafkaListenerContainerFactory 에 wire-in 될 빈.
     * stockCommittedProducerFactory 와 같은 인스턴스를 공유해야 transactional.id 정합이 유지된다.
     */
    @Bean
    public KafkaTransactionManager<String, String> kafkaTransactionManager(
            ProducerFactory<String, String> stockCommittedProducerFactory) {
        return new KafkaTransactionManager<>(stockCommittedProducerFactory);
    }

    /**
     * stock-committed 발행 전용 EOS KafkaTemplate.
     * EOS-aware ProducerFactory (stockCommittedProducerFactory) 기반 — Kafka 트랜잭션 안에서 발행된다.
     * {@link com.hyoguoo.paymentplatform.payment.application.usecase.PaymentConfirmResultUseCase} 가
     * APPROVED 결과 처리 시 이 템플릿으로 재고 확정 메시지를 발행한다.
     *
     * <p>PET-9 에서 StockOutbox 묶음이 제거되어 이 템플릿이 stock-committed 발행의 단일 경로다.
     */
    @Bean
    public KafkaTemplate<String, String> stockCommittedKafkaTemplate(
            ProducerFactory<String, String> stockCommittedProducerFactory,
            ObservationRegistry observationRegistry) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(stockCommittedProducerFactory);
        template.setDefaultTopic(eventsStockCommittedTopic);
        template.setObservationEnabled(true);
        template.setObservationRegistry(observationRegistry);
        return template;
    }

    /**
     * payment.commands.confirm 전용 ProducerFactory.
     * PET-6 에서 stockCommittedProducerFactory 가 명시 등록된 이후
     * Spring Boot auto-config 의 주 ProducerFactory 가 ambiguous 해져
     * JsonSerializer 기반 타입드 주입이 깨지는 문제를 방지하기 위해 명시 정의.
     * application.yml 의 spring.json.add.type.headers=false 와 동일한 설정 적용.
     */
    @Bean
    public ProducerFactory<String, PaymentConfirmCommandMessage> commandsConfirmProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(props);
    }

    /**
     * payment.commands.confirm 전용 템플릿.
     * 발행 주체: payment-service OutboxRelayService — pg-service 로 confirm 명령을 넘긴다.
     */
    @Bean
    public KafkaTemplate<String, PaymentConfirmCommandMessage> commandsConfirmKafkaTemplate(
            ProducerFactory<String, PaymentConfirmCommandMessage> commandsConfirmProducerFactory) {
        return buildObservedTemplate(commandsConfirmProducerFactory, commandsConfirmTopic);
    }

    /**
     * payment.events.confirmed.dlq 전용 String KafkaTemplate.
     * SCR-8 KafkaErrorHandlerConfig 의 DeadLetterPublishingRecoverer 가 DLQ 발행에 사용한다.
     * 별도 StringSerializer ProducerFactory 사용으로 JsonSerializer 혼용을 차단하고,
     * ObservationRegistry 를 명시 wiring 해 traceparent 전파 경로를 확보한다.
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
     * 토픽별 타입드 KafkaTemplate 공통 빌더 — defaultTopic 고정 + observation 활성화 (traceparent 자동 전파).
     */
    private static <T> KafkaTemplate<String, T> buildObservedTemplate(
            ProducerFactory<String, T> factory, String defaultTopic) {
        KafkaTemplate<String, T> template = new KafkaTemplate<>(factory);
        template.setDefaultTopic(defaultTopic);
        template.setObservationEnabled(true);
        return template;
    }
}
