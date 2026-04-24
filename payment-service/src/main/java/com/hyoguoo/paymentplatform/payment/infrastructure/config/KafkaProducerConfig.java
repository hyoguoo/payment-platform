package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.event.PaymentConfirmCommandMessage;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.event.StockCommittedEvent;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.event.StockRestoreEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import java.util.Map;

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
 * <p>ProducerFactory 는 Spring Boot 오토컨피그의 기본 빈을 재사용한다
 * (application.yml {@code spring.kafka.producer.*} 설정 적용: JsonSerializer 등).
 * 타입 파라미터는 제네릭 지우기로 런타임에 무관하므로 단일 팩토리를 모든 템플릿이 공유한다.
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

    @Value("${payment.kafka.topics.events-stock-restore}")
    private String eventsStockRestoreTopic;

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
     * payment.events.stock-committed 전용 템플릿.
     * 발행 주체: payment-service StockCommitEventKafkaPublisher — 결제 확정 시 재고 차감 이벤트.
     */
    @Bean
    public KafkaTemplate<String, StockCommittedEvent> stockCommittedKafkaTemplate(
            ProducerFactory<String, StockCommittedEvent> producerFactory) {
        return buildObservedTemplate(producerFactory, eventsStockCommittedTopic);
    }

    /**
     * stock.events.restore 전용 템플릿.
     * 발행 주체: payment-service StockRestoreEventKafkaPublisher — FAILED 결제 보상 이벤트.
     */
    @Bean
    public KafkaTemplate<String, StockRestoreEvent> stockRestoreKafkaTemplate(
            ProducerFactory<String, StockRestoreEvent> producerFactory) {
        return buildObservedTemplate(producerFactory, eventsStockRestoreTopic);
    }

    /**
     * payment.events.confirmed.dlq 전용 String KafkaTemplate.
     * T-C3: dedupe remove 실패 시 DLQ 전송용. String 페이로드(reason)만 전송.
     * 별도 StringSerializer ProducerFactory 사용 — JsonSerializer 혼용 방지.
     */
    @Bean
    public KafkaTemplate<String, String> confirmedDlqKafkaTemplate() {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        );
        ProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(props);
        KafkaTemplate<String, String> template = new KafkaTemplate<>(factory);
        template.setDefaultTopic(PaymentTopics.EVENTS_CONFIRMED_DLQ);
        template.setObservationEnabled(true);
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
