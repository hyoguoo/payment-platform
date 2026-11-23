package com.hyoguoo.paymentplatform.product.infrastructure.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.hyoguoo.paymentplatform.product.exception.ProductStockException
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.ProductTopics
import io.mockk.every
import io.mockk.mockk
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.internals.RecordHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.classify.BinaryExceptionClassifier
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.messaging.converter.MessageConversionException
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.BackOffExecution
import org.springframework.util.backoff.FixedBackOff
import java.util.function.BiFunction

@DisplayName("KafkaErrorHandlerConfig — DefaultErrorHandler bean 생성 및 설정 검증")
class KafkaErrorHandlerConfigTest {
    private val objectMapper = ObjectMapper()

    private lateinit var config: KafkaErrorHandlerConfig

    @BeforeEach
    fun setUp() {
        config = KafkaErrorHandlerConfig()
        ReflectionTestUtils.setField(config, "backoffInterval", 1000L)
        ReflectionTestUtils.setField(config, "maxAttempts", 5L)
    }

    @Test
    @DisplayName("errorHandler_빈_생성_성공 — Mock DeadLetterPublishingRecoverer 주입 시 DefaultErrorHandler 반환")
    fun `errorHandler_빈_생성_성공`() {
        val recoverer = buildRecoverer()

        val handler = config.kafkaErrorHandler(recoverer)

        assertThat(handler).isNotNull
    }

    @Test
    @DisplayName(
        "not_retryable_예외_목록_포함_확인 — 음수 가드 예외(ProductStockException)는 재시도 없이 즉시 격리," +
            " 재고 row 미존재(IllegalStateException)는 기존 재시도 경로를 그대로 탄다",
    )
    fun `not_retryable_예외_목록_포함_확인`() {
        val handler = config.kafkaErrorHandler(buildRecoverer())

        val classifier = ReflectionTestUtils.invokeMethod<BinaryExceptionClassifier>(handler, "getClassifier")

        assertThat(classifier).isNotNull
        // classify() returns false → not retryable (즉시 격리)
        assertThat(classifier!!.classify(MessageConversionException("test"))).isFalse
        assertThat(classifier.classify(IllegalArgumentException("test"))).isFalse
        assertThat(classifier.classify(ProductStockException.of(ProductErrorCode.NOT_ENOUGH_STOCK))).isFalse
        // 재고 row 미존재는 목록에 없음 — 재시도 대상(true)
        assertThat(classifier.classify(IllegalStateException("재고 row 없음"))).isTrue
        // 일반 RuntimeException 은 retryable (true)
        assertThat(classifier.classify(RuntimeException("transient"))).isTrue
    }

    @Test
    @DisplayName("backoff_설정값_반영 — interval=1000ms, maxAttempts=5 (6번째 호출에서 STOP)")
    fun `backoff_설정값_반영`() {
        val handler = config.kafkaErrorHandler(buildRecoverer())

        val failureTracker = ReflectionTestUtils.getField(handler, "failureTracker")
        assertThat(failureTracker).isNotNull
        val backOff = ReflectionTestUtils.getField(failureTracker!!, "backOff") as BackOff
        assertThat(backOff).isInstanceOf(FixedBackOff::class.java)

        val exec = backOff.start()
        assertThat(exec.nextBackOff()).isEqualTo(1000L)
        assertThat(exec.nextBackOff()).isEqualTo(1000L)
        assertThat(exec.nextBackOff()).isEqualTo(1000L)
        assertThat(exec.nextBackOff()).isEqualTo(1000L)
        assertThat(exec.nextBackOff()).isEqualTo(1000L)
        assertThat(exec.nextBackOff()).isEqualTo(BackOffExecution.STOP)
    }

    @Test
    @DisplayName(
        "dlq_destination_resolver_정합 — payment.events.stock-committed 처리 실패 시 발행 목적지가" +
            " PAYMENT_EVENTS_STOCK_COMMITTED_DLQ(.dlq)임을 검증",
    )
    @Suppress("UNCHECKED_CAST")
    fun `dlq_destination_resolver_정합`() {
        val recoverer = buildRecoverer()
        val handler = config.kafkaErrorHandler(recoverer)

        val failureTracker = ReflectionTestUtils.getField(handler, "failureTracker")
        assertThat(failureTracker).isNotNull
        val rawRecoverer = ReflectionTestUtils.invokeMethod<Any>(failureTracker!!, "getRecoverer")
        assertThat(rawRecoverer).isInstanceOf(DeadLetterPublishingRecoverer::class.java)
        val destinationResolver =
            ReflectionTestUtils.getField(rawRecoverer!!, "destinationResolver")
                as BiFunction<ConsumerRecord<*, *>, Exception, TopicPartition>?
        assertThat(destinationResolver).isNotNull

        val record = mockk<ConsumerRecord<String, String>>(relaxed = true)
        every { record.topic() } returns ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED
        every { record.partition() } returns 0
        val exception = RuntimeException("처리 실패 — retry 소진")

        val destination = destinationResolver!!.apply(record, exception)

        assertThat(destination.topic()).isEqualTo(ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED_DLQ)
        assertThat(destination.partition()).isEqualTo(0)
    }

    @Test
    @DisplayName(
        "quarantine_key는_orderId_productId_조합 — 원본 키가 productId 단독이어도 격리 메시지 키는" +
            " orderId:productId 로 재조합된다",
    )
    fun `quarantine_key는_orderId_productId_조합`() {
        val orderId = "order-777"
        val productId = 55L
        val json =
            "{\"productId\":$productId,\"qty\":3,\"idempotencyKey\":\"uuid-1\"," +
                "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"orderId\":\"$orderId\"," +
                "\"expiresAt\":\"2026-01-09T00:00:00Z\"}"
        val record =
            ConsumerRecord(
                ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED,
                0,
                10L,
                productId.toString(),
                json,
            )

        val outRecord = createProducerRecord(record)

        assertThat(outRecord).isNotNull
        assertThat(outRecord!!.key()).isEqualTo("$orderId:$productId")
    }

    @Test
    @DisplayName("quarantine_key_파싱실패시_원본키로_fallback — orderId 가 없는 페이로드는 원본 키를 그대로 쓴다")
    fun `quarantine_key_파싱실패시_원본키로_fallback`() {
        val rawKey = "55"
        val malformedJson = "not-a-json"
        val record =
            ConsumerRecord(
                ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED,
                0,
                10L,
                rawKey,
                malformedJson,
            )

        val outRecord = createProducerRecord(record)

        assertThat(outRecord).isNotNull
        assertThat(outRecord!!.key()).isEqualTo(rawKey)
    }

    @Test
    @DisplayName("deadLetterPublishingRecoverer_빈_생성_성공 — StockCommitQuarantineRecoverer 인스턴스를 반환")
    fun `deadLetterPublishingRecoverer_빈_생성_성공`() {
        val recoverer = buildRecoverer()

        assertThat(recoverer).isInstanceOf(StockCommitQuarantineRecoverer::class.java)
    }

    private fun createProducerRecord(record: ConsumerRecord<String, String>): ProducerRecord<Any, Any>? {
        val recoverer =
            StockCommitQuarantineRecoverer(
                mockk<KafkaTemplate<String, String>>(relaxed = true),
                objectMapper,
                BiFunction { rec, _ ->
                    TopicPartition(ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED_DLQ, rec.partition())
                },
            )

        return ReflectionTestUtils.invokeMethod<ProducerRecord<Any, Any>>(
            recoverer,
            "createProducerRecord",
            record,
            TopicPartition(ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED_DLQ, 0),
            RecordHeaders(),
            null,
            null,
        )
    }

    private fun buildRecoverer(): DeadLetterPublishingRecoverer {
        val mockTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true)
        return config.deadLetterPublishingRecoverer(mockTemplate, objectMapper)
    }
}
