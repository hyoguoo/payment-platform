package com.hyoguoo.paymentplatform.pg.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

/**
 * Kafka 메시지 컨버터 구성.
 * producer 는 JSON 문자열을 StringSerializer 로 전송하고 consumer 는 StringDeserializer 로 받는다.
 * 이 컨버터는 수신된 JSON String 을 @KafkaListener 파라미터 타입(POJO/record) 으로 변환한다.
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
