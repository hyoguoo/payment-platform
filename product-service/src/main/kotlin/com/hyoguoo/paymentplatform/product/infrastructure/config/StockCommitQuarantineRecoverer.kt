package com.hyoguoo.paymentplatform.product.infrastructure.config

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.hyoguoo.paymentplatform.product.core.common.log.EventType
import com.hyoguoo.paymentplatform.product.core.common.log.LogDomain
import com.hyoguoo.paymentplatform.product.core.common.log.LogFmt
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.Headers
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import java.util.function.BiFunction

/**
 * 재고 확정 소비 실패 메시지를 격리 토픽으로 보내는 recoverer.
 *
 * 격리 메시지의 Kafka 키를 원본 키(상품번호) 대신 `orderId:productId` 조합으로 다시 만든다. 운영자가 격리 토픽에서
 * 주문 단위로 사고를 찾을 수 있게 하기 위해서다. 페이로드에 orderId 나 productId 가 없거나 파싱할 수 없으면
 * 원본 키를 그대로 쓴다.
 */
class StockCommitQuarantineRecoverer(
    template: KafkaOperations<*, *>,
    private val objectMapper: ObjectMapper,
    destinationResolver: BiFunction<ConsumerRecord<*, *>, Exception, TopicPartition>,
) : DeadLetterPublishingRecoverer(template, destinationResolver) {
    override fun createProducerRecord(
        record: ConsumerRecord<*, *>,
        topicPartition: TopicPartition,
        headers: Headers,
        key: ByteArray?,
        value: ByteArray?,
    ): ProducerRecord<Any, Any> {
        val original = super.createProducerRecord(record, topicPartition, headers, key, value)
        val incidentKey = resolveIncidentKey(record) ?: return original
        return ProducerRecord(original.topic(), original.partition(), incidentKey, original.value(), original.headers())
    }

    @Suppress("SwallowedException") // 파싱할 수 없는 페이로드는 원본 키로 격리하는 것이 이 함수의 처리다
    private fun resolveIncidentKey(record: ConsumerRecord<*, *>): String? {
        val json = record.value() as? String ?: return null
        return try {
            val node = objectMapper.readTree(json)
            val orderId = node.path("orderId").asText(null)
            if (orderId.isNullOrBlank() || !node.hasNonNull("productId")) {
                LogFmt.warn(log, LogDomain.STOCK, EventType.STOCK_COMMIT_QUARANTINE_KEY_FALLBACK) {
                    "orderId 또는 productId 누락 — 원본 키로 격리: partition=${record.partition()} offset=${record.offset()}"
                }
                null
            } else {
                "$orderId:${node.path("productId").asLong()}"
            }
        } catch (e: JsonProcessingException) {
            LogFmt.warn(log, LogDomain.STOCK, EventType.STOCK_COMMIT_QUARANTINE_KEY_FALLBACK) {
                "페이로드 파싱 실패 — 원본 키로 격리: partition=${record.partition()} offset=${record.offset()}"
            }
            null
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(StockCommitQuarantineRecoverer::class.java)
    }
}
