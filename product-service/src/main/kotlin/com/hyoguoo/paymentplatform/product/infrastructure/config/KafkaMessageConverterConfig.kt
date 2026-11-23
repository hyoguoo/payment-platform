package com.hyoguoo.paymentplatform.product.infrastructure.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.support.converter.RecordMessageConverter
import org.springframework.kafka.support.converter.StringJsonMessageConverter

/**
 * 재고 확정 메시지(JSON 문자열)를 리스너 파라미터 타입으로 바꾸는 변환기.
 *
 * Spring Boot 가 만든 ObjectMapper 를 그대로 쓴다. Boot 설정은 모르는 필드에서 실패하지 않으므로, producer 가
 * 필드를 먼저 추가해도 메시지가 격리되지 않는다. `jacksonObjectMapper()` 처럼 새 매퍼로 바꾸면 이 동작이 사라진다.
 */
@Configuration
@ConditionalOnProperty(name = ["spring.kafka.bootstrap-servers"])
class KafkaMessageConverterConfig {
    @Bean
    fun recordMessageConverter(objectMapper: ObjectMapper): RecordMessageConverter =
        StringJsonMessageConverter(objectMapper)
}
