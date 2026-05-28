package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.transaction.KafkaTransactionManager;

/**
 * payment-service Kafka 컨슈머 컨테이너 팩토리 명시 설정.
 *
 * <p>Spring Boot auto-config 의 {@code kafkaListenerContainerFactory} 를 교체한다.
 * 명시 정의가 필요한 이유: auto-config 는 {@link KafkaTransactionManager} wire-in 경로를 제공하지 않아
 * EOS wiring (consumer offset commit 이 프로듀서 트랜잭션과 동행) 을 설정할 수 없다.
 *
 * <p>의존 빈:
 * <ul>
 *   <li>{@link ConsumerFactory} — Spring Boot auto-config 생성 (application.yml 기반).
 *       isolation.level=read_committed 는 application.yml
 *       {@code spring.kafka.consumer.properties.isolation.level} 로 설정한다.</li>
 *   <li>{@link KafkaTransactionManager} — stockCommittedProducerFactory 와 wiring 된 빈.
 *       wire-in 으로 consumer offset commit 이 Kafka tx 단위로 묶인다.</li>
 *   <li>{@link CommonErrorHandler} ({@code kafkaErrorHandler}) — KafkaErrorHandlerConfig 에서 등록.
 *       auto-config 시 자동 감지되던 빈을 명시 팩토리에서 직접 주입.</li>
 *   <li>{@link RecordMessageConverter} ({@code recordMessageConverter}) — KafkaMessageConverterConfig 에서 등록.
 *       마찬가지로 명시 주입.</li>
 * </ul>
 *
 * <p>EOS 동작 원리: {@code setKafkaAwareTransactionManager} 가 설정되면 Spring Kafka 가 컨슈머 진입 시
 * {@code producer.beginTransaction()} 을 자동 호출하고, offset commit 을 프로듀서 트랜잭션에 동행시킨다.
 * RuntimeException 으로 롤백되면 프로듀서 abort + 오프셋 미커밋 → 동일 메시지 재배달.
 *
 * <p>isolation.level=read_committed: payment-service 는 자기 자신이 발행한 stock-committed 메시지를
 * 컨슘하지 않으나, EOS 일관성 기준에 따라 read_committed 를 적용한다.
 * 주된 보호 대상은 abort 된 메시지가 컨슈머에 노출되지 않도록 막는 것.
 */
@Configuration
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class KafkaConsumerConfig {

    /**
     * EOS-aware {@code kafkaListenerContainerFactory} 빈.
     * {@link ConfirmedEventConsumer} 의 {@code containerFactory = "kafkaListenerContainerFactory"} 가 이 빈을 참조한다.
     *
     * @param consumerFactory       Spring Boot auto-config ConsumerFactory (application.yml 기반)
     * @param kafkaTransactionManager EOS-aware KafkaTransactionManager
     * @param kafkaErrorHandler     KafkaErrorHandlerConfig 에서 등록된 DefaultErrorHandler
     * @param recordMessageConverter KafkaMessageConverterConfig 에서 등록된 StringJsonMessageConverter
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTransactionManager<String, String> kafkaTransactionManager,
            CommonErrorHandler kafkaErrorHandler,
            RecordMessageConverter recordMessageConverter) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setKafkaAwareTransactionManager(kafkaTransactionManager);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.setRecordMessageConverter(recordMessageConverter);
        return factory;
    }
}
