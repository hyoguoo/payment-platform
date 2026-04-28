package com.hyoguoo.paymentplatform.pg.infrastructure.config;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * pg-service Kafka 프로듀서 — 토픽별 타입드 KafkaTemplate 빈 등록.
 *
 * <p>각 토픽은 전용 타입드 템플릿을 갖는다. 이 구조는 다음을 강제한다:
 * <ul>
 *   <li>토픽 이름은 application.yml 의 pg.kafka.topics.* 프로퍼티에서만 관리된다.</li>
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

    @Value("${pg.kafka.topics.events-confirmed}")
    private String eventsConfirmedTopic;

    @Value("${pg.kafka.topics.commands-confirm}")
    private String commandsConfirmTopic;

    @Value("${pg.kafka.topics.commands-confirm-dlq}")
    private String commandsConfirmDlqTopic;

    /**
     * payment.events.confirmed 전용 템플릿.
     * 발행 주체: pg-service (APPROVED/FAILED/QUARANTINED 이벤트).
     */
    @Bean
    public KafkaTemplate<String, ConfirmedEventPayload> confirmedEventKafkaTemplate(
            ProducerFactory<String, ConfirmedEventPayload> producerFactory) {
        return buildObservedTemplate(producerFactory, eventsConfirmedTopic);
    }

    /**
     * payment.commands.confirm 전용 템플릿 (재시도 발행).
     * 발행 주체: pg-service PgVendorCallService (재시도 available_at 지연 발행).
     */
    @Bean
    public KafkaTemplate<String, PgConfirmCommand> commandsConfirmKafkaTemplate(
            ProducerFactory<String, PgConfirmCommand> producerFactory) {
        return buildObservedTemplate(producerFactory, commandsConfirmTopic);
    }

    /**
     * payment.commands.confirm.dlq 전용 템플릿.
     * 발행 주체: pg-service PgVendorCallService (재시도 한도 소진 → DLQ).
     * 소비 주체: pg-service PaymentConfirmDlqConsumer — 같은 PgConfirmCommand 스키마를 기대한다.
     */
    @Bean
    public KafkaTemplate<String, PgConfirmCommand> commandsConfirmDlqKafkaTemplate(
            ProducerFactory<String, PgConfirmCommand> producerFactory) {
        return buildObservedTemplate(producerFactory, commandsConfirmDlqTopic);
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
