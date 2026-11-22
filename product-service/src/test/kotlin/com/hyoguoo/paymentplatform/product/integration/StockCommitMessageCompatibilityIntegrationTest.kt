package com.hyoguoo.paymentplatform.product.integration

import com.hyoguoo.paymentplatform.product.application.usecase.StockCommitUseCase
import com.hyoguoo.paymentplatform.product.exception.ProductStockException
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.ProductTopics
import com.hyoguoo.paymentplatform.product.support.KMySQLContainer
import com.ninjasquad.springmockk.SpykBean
import io.mockk.clearMocks
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.util.AopTestUtils
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Callable

/**
 * 재고 확정 메시지의 필드 누락·null·모르는 필드·빈 값 레코드를 실제 경로(Boot ObjectMapper 변환기, MySQL 중복 판정
 * 저장소, 트랜잭션 프록시, 에러 핸들러)로 흘려 지금 동작을 고정하는 특성 테스트.
 *
 * 정상 발행 경로는 6개 필드를 모두 채우므로 기존 테스트는 이 경로를 지나지 않는다. 메시지 DTO 의 선언이나
 * 변환기 설정이 바뀌면 같은 메시지가 격리되거나 조용히 사라질 수 있어, 시나리오마다 유스케이스 호출 횟수,
 * 격리 토픽 도달과 키, 재고, 중복 판정 행을 함께 단언한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EmbeddedKafka(
    partitions = 1,
    topics = [
        ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED,
        ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED_DLQ,
    ],
    bootstrapServersProperty = "spring.kafka.bootstrap-servers",
)
@Tag("integration")
@DisplayName("재고 확정 메시지 호환 특성 통합 검증")
class StockCommitMessageCompatibilityIntegrationTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var stockCommittedQuarantineKafkaTemplate: KafkaTemplate<String, String>

    @Autowired
    private lateinit var embeddedKafkaBroker: EmbeddedKafkaBroker

    @Autowired
    private lateinit var kafkaListenerEndpointRegistry: KafkaListenerEndpointRegistry

    // 컨텍스트에 등록된 빈은 트랜잭션 프록시다. 호출 기록은 프록시 안쪽의 스파이에 쌓이므로 그쪽을 꺼내 검증한다.
    @SpykBean
    private lateinit var stockCommitUseCaseBean: StockCommitUseCase

    private val stockCommitUseCase: StockCommitUseCase
        get() = AopTestUtils.getUltimateTargetObject(stockCommitUseCaseBean)

    private lateinit var bootstrapServers: String

    @BeforeEach
    fun setUp() {
        for (container in kafkaListenerEndpointRegistry.listenerContainers) {
            await()
                .atMost(Duration.ofSeconds(30))
                .until(
                    Callable {
                        container.assignedPartitions != null && container.assignedPartitions!!.isNotEmpty()
                    },
                )
        }
        jdbcTemplate.update("DELETE FROM stock")
        jdbcTemplate.update("DELETE FROM product")
        jdbcTemplate.update("DELETE FROM stock_commit_dedupe")
        clearMocks(stockCommitUseCase, answers = false)
        bootstrapServers = embeddedKafkaBroker.brokersAsString
    }

    @Test
    @DisplayName("FS-3 재고 행 없음 — 재시도 뒤 격리되고 중복 판정 행이 남지 않는다")
    fun rowNotFound_rollsBackDedupeRecord() {
        val productId = 9101L
        val orderId = "order-fs3-missing-" + UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID().toString()

        send(productId, fullPayload(productId, 1, idempotencyKey, orderId))

        val quarantined = pollQuarantine(byOrderId(orderId), QUARANTINE_WAIT)
        assertThat(quarantined).hasSize(1)
        assertThat(causeOf(quarantined[0])).isEqualTo(IllegalStateException::class.java.name)
        assertThat(countDedupe(idempotencyKey)).isZero
    }

    @Test
    @DisplayName("FS-3 재고 부족 — 격리되고 중복 판정 행이 남지 않는다")
    fun insufficientStock_rollsBackDedupeRecord() {
        val productId = 9102L
        val orderId = "order-fs3-insufficient-" + UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID().toString()
        seedProduct(productId, 2)

        send(productId, fullPayload(productId, 5, idempotencyKey, orderId))

        val quarantined = pollQuarantine(byOrderId(orderId), QUARANTINE_WAIT)
        assertThat(quarantined).hasSize(1)
        assertThat(causeOf(quarantined[0])).isEqualTo(ProductStockException::class.java.name)
        assertThat(countDedupe(idempotencyKey)).isZero
        assertThat(stockOf(productId)).isEqualTo(2)
    }

    @Test
    @DisplayName("FS-4 occurredAt·expiresAt·orderId 가 null — 차감되고 만료 시각은 수신 시각 + 8일, orderId 는 빈 문자열")
    fun nullOptionalFields_areFilledWithFallbacks() {
        val productId = 9103L
        val idempotencyKey = UUID.randomUUID().toString()
        seedProduct(productId, 10)

        val before = Instant.now()
        send(
            productId,
            "{\"productId\":" + productId + ",\"qty\":3,\"idempotencyKey\":\"" + idempotencyKey +
                "\",\"occurredAt\":null,\"orderId\":null,\"expiresAt\":null}",
        )

        awaitStock(productId, 7)
        val after = Instant.now()
        verify(exactly = 1) {
            stockCommitUseCase.commit(idempotencyKey, "", productId, 3, any(), any())
        }
        assertThat(expiresAtOf(idempotencyKey))
            .isBetween(before.plus(DEDUPE_TTL).minusSeconds(1), after.plus(DEDUPE_TTL).plusSeconds(1))
    }

    @Test
    @DisplayName("FS-4 expiresAt·orderId 가 빠짐 — 차감되고 만료 시각은 occurredAt + 8일")
    fun absentOptionalFields_useOccurredAtForExpiry() {
        val productId = 9104L
        val idempotencyKey = UUID.randomUUID().toString()
        seedProduct(productId, 10)

        send(
            productId,
            "{\"productId\":" + productId + ",\"qty\":4,\"idempotencyKey\":\"" + idempotencyKey +
                "\",\"occurredAt\":\"" + OCCURRED_AT + "\"}",
        )

        awaitStock(productId, 6)
        verify(exactly = 1) {
            stockCommitUseCase.commit(idempotencyKey, "", productId, 4, any(), any())
        }
        assertThat(expiresAtOf(idempotencyKey)).isEqualTo(OCCURRED_AT.plus(DEDUPE_TTL))
    }

    @Test
    @DisplayName("FS-5 productId 가 null — 유스케이스를 부르지 못하고 재시도 뒤 원본 키로 격리된다")
    fun nullProductId_quarantinesWithOriginalKey() {
        val orderId = "order-fs5-null-" + UUID.randomUUID()
        val kafkaKey = "fs5-null-" + UUID.randomUUID()

        stockCommittedQuarantineKafkaTemplate.send(
            ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED,
            kafkaKey,
            "{\"productId\":null,\"qty\":1,\"idempotencyKey\":\"" + UUID.randomUUID() +
                "\",\"occurredAt\":\"" + OCCURRED_AT + "\",\"orderId\":\"" + orderId +
                "\",\"expiresAt\":\"" + OCCURRED_AT.plus(DEDUPE_TTL) + "\"}",
        )

        val quarantined = pollQuarantine(byOrderId(orderId), QUARANTINE_WAIT)
        assertThat(quarantined).hasSize(1)
        assertThat(quarantined[0].key()).isEqualTo(kafkaKey)
        assertThat(causeOf(quarantined[0])).isEqualTo(NullPointerException::class.java.name)
        verifyNeverCommitted()
        assertThat(countAllDedupe()).isZero
    }

    @Test
    @DisplayName("FS-5 productId 가 빠짐 — 유스케이스를 부르지 못하고 재시도 뒤 원본 키로 격리된다")
    fun absentProductId_quarantinesWithOriginalKey() {
        val orderId = "order-fs5-absent-" + UUID.randomUUID()
        val kafkaKey = "fs5-absent-" + UUID.randomUUID()

        stockCommittedQuarantineKafkaTemplate.send(
            ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED,
            kafkaKey,
            "{\"qty\":1,\"idempotencyKey\":\"" + UUID.randomUUID() +
                "\",\"occurredAt\":\"" + OCCURRED_AT + "\",\"orderId\":\"" + orderId + "\"}",
        )

        val quarantined = pollQuarantine(byOrderId(orderId), QUARANTINE_WAIT)
        assertThat(quarantined).hasSize(1)
        assertThat(quarantined[0].key()).isEqualTo(kafkaKey)
        assertThat(causeOf(quarantined[0])).isEqualTo(NullPointerException::class.java.name)
        verifyNeverCommitted()
        assertThat(countAllDedupe()).isZero
    }

    @Test
    @DisplayName("FS-5b qty 가 빠짐 — 0 으로 들어와 재고는 그대로이고 중복 판정 행만 남는다")
    fun absentQty_isTreatedAsZero() {
        val productId = 9105L
        val orderId = "order-fs5b-absent-" + UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID().toString()
        seedProduct(productId, 10)

        send(
            productId,
            "{\"productId\":" + productId + ",\"idempotencyKey\":\"" + idempotencyKey +
                "\",\"occurredAt\":\"" + OCCURRED_AT + "\",\"orderId\":\"" + orderId + "\"}",
        )

        await().atMost(QUARANTINE_WAIT).untilAsserted { assertThat(countDedupe(idempotencyKey)).isEqualTo(1) }
        verify(exactly = 1) {
            stockCommitUseCase.commit(idempotencyKey, orderId, productId, 0, any(), any())
        }
        assertThat(stockOf(productId)).isEqualTo(10)
        assertThat(pollQuarantine(byOrderId(orderId), ABSENCE_WAIT)).isEmpty()
    }

    @Test
    @DisplayName("FS-5b qty 가 null — 0 으로 들어와 재고는 그대로이고 중복 판정 행만 남는다")
    fun nullQty_isTreatedAsZero() {
        val productId = 9106L
        val orderId = "order-fs5b-null-" + UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID().toString()
        seedProduct(productId, 10)

        send(
            productId,
            "{\"productId\":" + productId + ",\"qty\":null,\"idempotencyKey\":\"" + idempotencyKey +
                "\",\"occurredAt\":\"" + OCCURRED_AT + "\",\"orderId\":\"" + orderId + "\"}",
        )

        await().atMost(QUARANTINE_WAIT).untilAsserted { assertThat(countDedupe(idempotencyKey)).isEqualTo(1) }
        verify(exactly = 1) {
            stockCommitUseCase.commit(idempotencyKey, orderId, productId, 0, any(), any())
        }
        assertThat(stockOf(productId)).isEqualTo(10)
        assertThat(pollQuarantine(byOrderId(orderId), ABSENCE_WAIT)).isEmpty()
    }

    @Test
    @DisplayName("FS-12 idempotencyKey 가 null 인 메시지 2건 — 첫 건만 차감되고 두 번째는 중복으로 무시된다")
    fun nullIdempotencyKey_secondMessageIsTreatedAsDuplicate() {
        val productId = 9107L
        val orderId1 = "order-fs12-first-" + UUID.randomUUID()
        val orderId2 = "order-fs12-second-" + UUID.randomUUID()
        seedProduct(productId, 10)

        send(productId, payloadWithKeyLiteral(productId, 2, "null", orderId1))
        awaitStock(productId, 8)
        send(productId, payloadWithKeyLiteral(productId, 3, "null", orderId2))

        await().atMost(QUARANTINE_WAIT).untilAsserted {
            verify(exactly = 1) {
                stockCommitUseCase.commit(null, orderId2, productId, 3, any(), any())
            }
        }
        assertThat(stockOf(productId)).isEqualTo(8)
        assertThat(jdbcTemplate.queryForList("SELECT event_uuid FROM stock_commit_dedupe", String::class.java))
            .containsExactly("")
        assertThat(pollQuarantine(byOrderId(orderId2), ABSENCE_WAIT)).isEmpty()
    }

    @Test
    @DisplayName("FS-12 idempotencyKey 가 빠진 메시지 — null 과 같이 빈 문자열 키로 기록되고 차감된다")
    fun absentIdempotencyKey_isRecordedAsEmptyKey() {
        val productId = 9108L
        val orderId = "order-fs12-absent-" + UUID.randomUUID()
        seedProduct(productId, 10)

        send(
            productId,
            "{\"productId\":" + productId + ",\"qty\":2" +
                ",\"occurredAt\":\"" + OCCURRED_AT + "\",\"orderId\":\"" + orderId + "\"}",
        )

        awaitStock(productId, 8)
        verify(exactly = 1) {
            stockCommitUseCase.commit(null, orderId, productId, 2, any(), any())
        }
        assertThat(jdbcTemplate.queryForList("SELECT event_uuid FROM stock_commit_dedupe", String::class.java))
            .containsExactly("")
    }

    @Test
    @DisplayName("FS-13 모르는 필드가 있는 메시지 — 무시하고 차감한다")
    fun unknownField_isIgnored() {
        val productId = 9109L
        val orderId = "order-fs13-" + UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID().toString()
        seedProduct(productId, 10)

        val payload = fullPayload(productId, 4, idempotencyKey, orderId)
        send(productId, payload.substring(0, payload.length - 1) + ",\"newField\":{\"nested\":true}}")

        awaitStock(productId, 6)
        verify(exactly = 1) {
            stockCommitUseCase.commit(idempotencyKey, orderId, productId, 4, any(), any())
        }
        assertThat(countDedupe(idempotencyKey)).isEqualTo(1)
    }

    @Test
    @DisplayName("FS-14 값이 null 인 레코드 — 유스케이스를 부르지 않고 원본 키로 격리된다")
    fun tombstone_quarantinesWithOriginalKey() {
        val kafkaKey = "fs14-tombstone-" + UUID.randomUUID()

        stockCommittedQuarantineKafkaTemplate.send(ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED, kafkaKey, null)

        val quarantined = pollQuarantine({ rec -> kafkaKey == rec.key() }, QUARANTINE_WAIT)
        assertThat(quarantined).hasSize(1)
        assertThat(quarantined[0].value()).isNull()
        // 리스너 파라미터가 필수 본문이라 Spring 이 값 없음으로 거부한다. 파라미터를 nullable 로 바꾸면 null 이 넘어간다.
        assertThat(causeOf(quarantined[0])).isEqualTo(MethodArgumentNotValidException::class.java.name)
        verifyNeverCommitted()
        assertThat(countAllDedupe()).isZero
    }

    /** 어떤 인자로도 유스케이스가 불리지 않았음을 확인한다. null 인자도 같은 범위로 맞춘다. */
    private fun verifyNeverCommitted() {
        verify(exactly = 0) {
            stockCommitUseCase.commit(any(), any(), any(), any(), any(), any())
        }
    }

    private fun send(
        productId: Long,
        payload: String,
    ) {
        stockCommittedQuarantineKafkaTemplate.send(
            ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED,
            productId.toString(),
            payload,
        )
    }

    private fun fullPayload(
        productId: Long,
        qty: Int,
        idempotencyKey: String,
        orderId: String,
    ): String = payloadWithKeyLiteral(productId, qty, "\"" + idempotencyKey + "\"", orderId)

    private fun payloadWithKeyLiteral(
        productId: Long,
        qty: Int,
        idempotencyKeyLiteral: String,
        orderId: String,
    ): String =
        "{\"productId\":" + productId + ",\"qty\":" + qty +
            ",\"idempotencyKey\":" + idempotencyKeyLiteral +
            ",\"occurredAt\":\"" + OCCURRED_AT + "\"" +
            ",\"orderId\":\"" + orderId + "\"" +
            ",\"expiresAt\":\"" + OCCURRED_AT.plus(DEDUPE_TTL) + "\"}"

    private fun seedProduct(
        productId: Long,
        quantity: Int,
    ) {
        jdbcTemplate.update(
            "INSERT INTO product (id, name, price, description, seller_id) VALUES (?, ?, ?, ?, ?)",
            productId,
            "compat-test-product",
            1000,
            "compat test",
            1L,
        )
        jdbcTemplate.update("INSERT INTO stock (product_id, quantity) VALUES (?, ?)", productId, quantity)
    }

    private fun awaitStock(
        productId: Long,
        expected: Int,
    ) {
        await().atMost(QUARANTINE_WAIT).untilAsserted { assertThat(stockOf(productId)).isEqualTo(expected) }
    }

    private fun stockOf(productId: Long): Int? =
        jdbcTemplate.queryForObject(
            "SELECT quantity FROM stock WHERE product_id = ?",
            Int::class.javaObjectType,
            productId,
        )

    private fun countDedupe(idempotencyKey: String): Int? =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM stock_commit_dedupe WHERE event_uuid = ?",
            Int::class.javaObjectType,
            idempotencyKey,
        )

    private fun countAllDedupe(): Int? =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_commit_dedupe", Int::class.javaObjectType)

    /**
     * 저장소(`JdbcEventDedupeStore`)가 UTC 달력으로 바인딩해 쓰므로 같은 달력으로 읽는다.
     * 달력 없이 읽으면 JVM 기본 시간대만큼 어긋난다.
     */
    private fun expiresAtOf(idempotencyKey: String): Instant {
        val expiresAt =
            jdbcTemplate.queryForObject(
                "SELECT expires_at FROM stock_commit_dedupe WHERE event_uuid = ?",
                RowMapper<Timestamp> { rs, _ ->
                    rs.getTimestamp("expires_at", Calendar.getInstance(TimeZone.getTimeZone("UTC")))
                },
                idempotencyKey,
            )
        assertThat(expiresAt).isNotNull
        return expiresAt!!.toInstant()
    }

    private fun pollQuarantine(
        filter: (ConsumerRecord<String, String>) -> Boolean,
        timeout: Duration,
    ): List<ConsumerRecord<String, String>> {
        val props =
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to "test-compat-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to "false",
            )

        val result = mutableListOf<ConsumerRecord<String, String>>()
        KafkaConsumer<String, String>(props).use { consumer ->
            consumer.subscribe(listOf(ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED_DLQ))
            val deadline = System.currentTimeMillis() + timeout.toMillis()
            while (System.currentTimeMillis() < deadline && result.isEmpty()) {
                val records = consumer.poll(Duration.ofMillis(500))
                result.addAll(records.filter { rec -> filter(rec) })
            }
        }
        return result
    }

    companion object {
        private val OCCURRED_AT: Instant = Instant.parse("2026-01-01T00:00:00Z")
        private val DEDUPE_TTL: Duration = Duration.ofDays(8)
        private val QUARANTINE_WAIT: Duration = Duration.ofSeconds(10)
        private val ABSENCE_WAIT: Duration = Duration.ofSeconds(3)

        private val MYSQL_CONTAINER: KMySQLContainer =
            KMySQLContainer("mysql:8.0")
                .withDatabaseName("product-compat-test")
                .withUsername("test")
                .withPassword("test")
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_unicode_ci",
                ).withReuse(true)

        init {
            MYSQL_CONTAINER.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun overrideProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { MYSQL_CONTAINER.jdbcUrl }
            registry.add("spring.datasource.username") { MYSQL_CONTAINER.username }
            registry.add("spring.datasource.password") { MYSQL_CONTAINER.password }
            registry.add("spring.datasource.driver-class-name") { "com.mysql.cj.jdbc.Driver" }
            registry.add("spring.flyway.locations") { "classpath:db/schema" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("scheduler.enabled") { "false" }
            registry.add("product.kafka.error-handler.backoff.interval") { "100" }
            registry.add("product.kafka.error-handler.backoff.max-attempts") { "2" }
        }

        private fun byOrderId(orderId: String): (ConsumerRecord<String, String>) -> Boolean =
            { rec -> rec.value() != null && rec.value().contains("\"orderId\":\"" + orderId + "\"") }

        private fun causeOf(rec: ConsumerRecord<String, String>): String {
            val header = rec.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)
            assertThat(header).`as`("격리 메시지에 원인 예외 헤더가 없다").isNotNull
            return String(header.value(), StandardCharsets.UTF_8)
        }
    }
}
