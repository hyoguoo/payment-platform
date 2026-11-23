package com.hyoguoo.paymentplatform.product.integration

import com.hyoguoo.paymentplatform.product.application.usecase.StockCommitUseCase
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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.util.AopTestUtils
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable

/**
 * 재고 확정 음수 가드(Task 12)가 던지는 예외가 실제로 격리 토픽에 도달하는지 확인하는 통합 테스트
 * (STOCK-GATE-PER-PRODUCT Task 13).
 *
 * 가드만 있고 받을 곳이 없으면 사고가 막히는 게 아니라 조용히 사라진다 — 그래서 가드(재고 부족
 * 시 예외)와 격리 도달(실제로 그 예외가 `payment.events.stock-committed.dlq` 에 실리는지)을
 * 한 검증으로 묶는다.
 *
 * 1. #1 재고 부족(ProductStockException, not-retryable) — 재시도 없이 즉시 격리, 키는
 *    orderId:productId 조합
 * 2. #2 재고 row 미존재(IllegalStateException, retryable) — 재시도를 거쳐 격리되고, 그 뒤에 온
 *    정상 메시지는 정상 처리된다(컨슈머가 죽지 않는다)
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
@DisplayName("재고 확정 소비 실패 → 격리 토픽 도달 통합 검증")
class StockCommitQuarantineIntegrationTest {
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
        // cold-start 방어 — consumer group join + partition assignment 완료를 기다린다.
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
    @DisplayName("#1 재고 부족 — 재시도 없이 즉시 격리 토픽 도달, 키는 orderId:productId 조합")
    fun insufficientStock_quarantinesImmediately_withOrderProductKey() {
        val productId = 9001L
        val orderId = "order-insufficient-" + UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID().toString()
        seedProduct(productId, 2)

        val payload = buildPayload(productId, 5, idempotencyKey, orderId)
        stockCommittedQuarantineKafkaTemplate.send(
            ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED,
            productId.toString(),
            payload,
        )

        val quarantined = pollQuarantine(orderId, Duration.ofSeconds(10))

        assertThat(quarantined).hasSize(1)
        assertThat(quarantined[0].key()).isEqualTo("$orderId:$productId")
        assertThat(quarantined[0].value()).isEqualTo(payload)

        // 재시도 없이 단 한 번만 호출됐다 — not-retryable 목록 등재 효과
        verify(exactly = 1) {
            stockCommitUseCase.commit(idempotencyKey, orderId, productId, 5, any(), any())
        }

        // 잔량은 변하지 않는다 — 저장이 일어나지 않았다
        val quantity =
            jdbcTemplate.queryForObject(
                "SELECT quantity FROM stock WHERE product_id = ?",
                Int::class.javaObjectType,
                productId,
            )
        assertThat(quantity).isEqualTo(2)
    }

    @Test
    @DisplayName("#2 재고 row 미존재 — 재시도를 거쳐 격리되고, 뒤따르는 정상 메시지는 계속 처리된다")
    fun rowNotFound_retriesThenQuarantines_subsequentMessageStillProcessed() {
        val missingProductId = 9002L
        val orderId1 = "order-notfound-" + UUID.randomUUID()
        val idempotencyKey1 = UUID.randomUUID().toString()
        // productId 9002 는 의도적으로 시드하지 않는다 — commitToRdb 가 IllegalStateException 을 던진다

        val healthyProductId = 9003L
        val orderId2 = "order-followup-" + UUID.randomUUID()
        val idempotencyKey2 = UUID.randomUUID().toString()
        seedProduct(healthyProductId, 10)

        val badPayload = buildPayload(missingProductId, 1, idempotencyKey1, orderId1)
        stockCommittedQuarantineKafkaTemplate.send(
            ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED,
            missingProductId.toString(),
            badPayload,
        )

        // 첫 메시지가 재시도를 소진하고 격리 토픽에 도달할 때까지 대기 — 그 사이 두 번째 메시지는 아직 보내지 않는다
        val quarantined1 = pollQuarantine(orderId1, Duration.ofSeconds(10))
        assertThat(quarantined1).hasSize(1)
        assertThat(quarantined1[0].key()).isEqualTo("$orderId1:$missingProductId")

        // 단일 시도가 아니라 반복 실패했다 — retryable 목록에 없어 재시도 경로를 탄 증거
        verify(atLeast = 2) {
            stockCommitUseCase.commit(idempotencyKey1, orderId1, missingProductId, 1, any(), any())
        }

        val goodPayload = buildPayload(healthyProductId, 3, idempotencyKey2, orderId2)
        stockCommittedQuarantineKafkaTemplate.send(
            ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED,
            healthyProductId.toString(),
            goodPayload,
        )

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val quantity =
                jdbcTemplate.queryForObject(
                    "SELECT quantity FROM stock WHERE product_id = ?",
                    Int::class.javaObjectType,
                    healthyProductId,
                )
            assertThat(quantity).isEqualTo(7)
        }

        // 뒤따르는 메시지는 격리로 가지 않는다
        val quarantined2 = pollQuarantine(orderId2, Duration.ofSeconds(2))
        assertThat(quarantined2).isEmpty()
    }

    private fun seedProduct(
        productId: Long,
        quantity: Int,
    ) {
        jdbcTemplate.update(
            "INSERT INTO product (id, name, price, description, seller_id) VALUES (?, ?, ?, ?, ?)",
            productId,
            "quarantine-test-product",
            1000,
            "quarantine test",
            1L,
        )
        jdbcTemplate.update(
            "INSERT INTO stock (product_id, quantity) VALUES (?, ?)",
            productId,
            quantity,
        )
    }

    private fun buildPayload(
        productId: Long,
        qty: Int,
        idempotencyKey: String,
        orderId: String,
    ): String {
        val occurredAt = Instant.parse("2026-01-01T00:00:00Z")
        val expiresAt = occurredAt.plus(Duration.ofDays(8))
        return "{\"productId\":" + productId + ",\"qty\":" + qty +
            ",\"idempotencyKey\":\"" + idempotencyKey + "\"" +
            ",\"occurredAt\":\"" + occurredAt + "\"" +
            ",\"orderId\":\"" + orderId + "\"" +
            ",\"expiresAt\":\"" + expiresAt + "\"}"
    }

    /**
     * 격리 토픽을 폴링해 지정 orderId 에 해당하는 레코드 목록(키 포함)을 반환한다.
     * 원본 페이로드 안의 `orderId` 필드로 필터링한다 — 격리 메시지의 Kafka 키는
     * `orderId:productId` 조합으로 바뀌어 있어 orderId 단독 키 매칭이 불가능하다.
     */
    private fun pollQuarantine(
        filterOrderId: String,
        timeout: Duration,
    ): List<ConsumerRecord<String, String>> {
        val props =
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to "test-quarantine-reader-" + UUID.randomUUID(),
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
                result.addAll(
                    records.filter { record ->
                        record.value() != null && record.value().contains("\"orderId\":\"" + filterOrderId + "\"")
                    },
                )
            }
        }
        return result
    }

    companion object {
        private val MYSQL_CONTAINER: KMySQLContainer =
            KMySQLContainer("mysql:8.0")
                .withDatabaseName("product-quarantine-test")
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
            // seed(V2) 배제 — 각 케이스가 심은 행만으로 단정한다 (docker profile / ProductRepositoryImplPageTest 와 동일 방식)
            registry.add("spring.flyway.locations") { "classpath:db/schema" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("scheduler.enabled") { "false" }
            // backoff 단축 — 반복 실패 시나리오 대기 시간 축소
            registry.add("product.kafka.error-handler.backoff.interval") { "100" }
            registry.add("product.kafka.error-handler.backoff.max-attempts") { "2" }
        }
    }
}
