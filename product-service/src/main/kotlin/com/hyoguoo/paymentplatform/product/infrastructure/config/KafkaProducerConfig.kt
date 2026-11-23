package com.hyoguoo.paymentplatform.product.infrastructure.config

import com.hyoguoo.paymentplatform.product.infrastructure.messaging.ProductTopics
import io.micrometer.observation.ObservationRegistry
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate

/**
 * product-service Kafka 프로듀서 — 격리 토픽 전용 타입드 KafkaTemplate 빈 등록.
 *
 * product-service 는 원래 consumer 전용이었다(재고 확정 커밋 소비). 음수 가드(Task 12)가 던지는
 * 예외를 받을 곳이 없어(STOCK-GATE-PER-PRODUCT Task 13) 격리 토픽과 함께 첫 producer 를 둔다.
 * payment-service `KafkaProducerConfig` 와 동일하게 토픽 전용 StringSerializer
 * ProducerFactory 를 명시 정의한다 — Spring Boot 오토컨피그 기본 KafkaTemplate 을 그대로 쓰면
 * 향후 다른 타입 페이로드용 ProducerFactory 가 추가될 때 ambiguous 주입 문제가 생긴다.
 */
@Configuration
@ConditionalOnProperty(name = ["spring.kafka.bootstrap-servers"])
class KafkaProducerConfig(
    @Value("\${spring.kafka.bootstrap-servers}")
    private val bootstrapServers: String,
) {
    /**
     * [ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED_DLQ] 전용 String KafkaTemplate.
     * `KafkaErrorHandlerConfig` 의 [StockCommitQuarantineRecoverer] 가 격리 토픽 발행에 사용한다.
     */
    @Bean
    fun stockCommittedQuarantineKafkaTemplate(observationRegistry: ObservationRegistry): KafkaTemplate<String, String> {
        val props =
            mapOf<String, Any>(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            )
        val template = KafkaTemplate(DefaultKafkaProducerFactory<String, String>(props))
        template.defaultTopic = ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED_DLQ
        template.setObservationEnabled(true)
        template.setObservationRegistry(observationRegistry)
        return template
    }
}
