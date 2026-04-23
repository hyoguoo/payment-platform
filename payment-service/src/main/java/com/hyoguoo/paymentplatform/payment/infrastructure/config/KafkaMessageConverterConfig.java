package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

/**
 * Kafka 메시지 컨버터 구성.
 * ADR-04/30: producer는 JsonSerializer로 POJO를 JSON 바이트로 직렬화하고
 * consumer는 StringDeserializer로 JSON 바이트를 String 으로 받은 뒤 이 컨버터로 POJO/record 로 변환한다.
 * 와이어 포맷은 양쪽 모두 JSON 바이트이므로 producer 직렬화 방식 변경(String → JsonSerializer)과 무관하게 호환된다.
 *
 * <p>Spring Boot Kafka 오토컨피그는 RecordMessageConverter 빈을 감지하면
 * 기본 ConcurrentKafkaListenerContainerFactory 에 자동 주입한다.
 * 별도 ContainerFactory 선언 없이 이 빈 하나만 제공하면 모든 @KafkaListener 에 적용된다.
 */
@Configuration
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class KafkaMessageConverterConfig {

    @Bean
    public RecordMessageConverter recordMessageConverter(ObjectMapper objectMapper) {
        return new StringJsonMessageConverter(objectMapper);
    }
}
