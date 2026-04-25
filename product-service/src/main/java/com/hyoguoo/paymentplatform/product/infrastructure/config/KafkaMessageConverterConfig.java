package com.hyoguoo.paymentplatform.product.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

/**
 * Kafka 메시지 컨버터 구성.
 * ADR-30: producer 는 JSON String 으로 발행, consumer 도 StringDeserializer 로 받는다.
 * RecordMessageConverter 빈 → 기본 ConcurrentKafkaListenerContainerFactory 자동 주입.
 *
 * <p>StockCommitConsumer / StockRestoreConsumer 가 record 타입(StockCommittedMessage 등) 으로
 * 파라미터를 선언했지만, RecordMessageConverter 부재 시 String 으로만 도착해 변환 실패.
 * pg-service KafkaMessageConverterConfig 와 동일 패턴(ADR-19 복제(b) 방침).
 */
@Configuration
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class KafkaMessageConverterConfig {

    @Bean
    public RecordMessageConverter recordMessageConverter(ObjectMapper objectMapper) {
        return new StringJsonMessageConverter(objectMapper);
    }
}
