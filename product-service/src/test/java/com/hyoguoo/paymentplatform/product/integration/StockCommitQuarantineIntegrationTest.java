package com.hyoguoo.paymentplatform.product.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hyoguoo.paymentplatform.product.application.usecase.StockCommitUseCase;
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.ProductTopics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;

/**
 * 재고 확정 음수 가드(Task 12)가 던지는 예외가 실제로 격리 토픽에 도달하는지 확인하는 통합 테스트
 * (STOCK-GATE-PER-PRODUCT Task 13).
 *
 * <p>가드만 있고 받을 곳이 없으면 사고가 막히는 게 아니라 조용히 사라진다 — 그래서 가드(재고 부족
 * 시 예외)와 격리 도달(실제로 그 예외가 {@code payment.events.stock-committed.dlq} 에 실리는지)을
 * 한 검증으로 묶는다.
 *
 * <ol>
 *   <li>#1 재고 부족(ProductStockException, not-retryable) — 재시도 없이 즉시 격리, 키는
 *   orderId:productId 조합</li>
 *   <li>#2 재고 row 미존재(IllegalStateException, retryable) — 재시도를 거쳐 격리되고, 그 뒤에 온
 *   정상 메시지는 정상 처리된다(컨슈머가 죽지 않는다)</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EmbeddedKafka(
        partitions = 1,
        topics = {
                ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED,
                ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED_DLQ
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Tag("integration")
@DisplayName("재고 확정 소비 실패 → 격리 토픽 도달 통합 검증")
class StockCommitQuarantineIntegrationTest {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("product-quarantine-test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand(
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_unicode_ci"
                    )
                    .withReuse(true);

    static {
        MYSQL_CONTAINER.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        // seed(V2) 배제 — 각 케이스가 심은 행만으로 단정한다 (docker profile / ProductRepositoryImplPageTest 와 동일 방식)
        registry.add("spring.flyway.locations", () -> "classpath:db/schema");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("scheduler.enabled", () -> "false");
        // backoff 단축 — 반복 실패 시나리오 대기 시간 축소
        registry.add("product.kafka.error-handler.backoff.interval", () -> "100");
        registry.add("product.kafka.error-handler.backoff.max-attempts", () -> "2");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaTemplate<String, String> stockCommittedQuarantineKafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @MockitoSpyBean
    private StockCommitUseCase stockCommitUseCase;

    private String bootstrapServers;

    @BeforeEach
    void setUp() {
        // cold-start 방어 — consumer group join + partition assignment 완료를 기다린다.
        for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> container.getAssignedPartitions() != null
                            && !container.getAssignedPartitions().isEmpty());
        }
        jdbcTemplate.update("DELETE FROM stock");
        jdbcTemplate.update("DELETE FROM product");
        jdbcTemplate.update("DELETE FROM stock_commit_dedupe");
        clearInvocations(stockCommitUseCase);
        bootstrapServers = embeddedKafkaBroker.getBrokersAsString();
    }

    @Test
    @DisplayName("#1 재고 부족 — 재시도 없이 즉시 격리 토픽 도달, 키는 orderId:productId 조합")
    void insufficientStock_quarantinesImmediately_withOrderProductKey() {
        long productId = 9001L;
        String orderId = "order-insufficient-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        seedProduct(productId, 2);

        String payload = buildPayload(productId, 5, idempotencyKey, orderId);
        stockCommittedQuarantineKafkaTemplate.send(
                ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED, String.valueOf(productId), payload);

        List<ConsumerRecord<String, String>> quarantined = pollQuarantine(orderId, Duration.ofSeconds(10));

        assertThat(quarantined).hasSize(1);
        assertThat(quarantined.get(0).key()).isEqualTo(orderId + ":" + productId);
        assertThat(quarantined.get(0).value()).isEqualTo(payload);

        // 재시도 없이 단 한 번만 호출됐다 — not-retryable 목록 등재 효과
        verify(stockCommitUseCase, times(1))
                .commit(eq(idempotencyKey), eq(orderId), eq(productId), eq(5), any(), any());

        // 잔량은 변하지 않는다 — 저장이 일어나지 않았다
        Integer quantity = jdbcTemplate.queryForObject(
                "SELECT quantity FROM stock WHERE product_id = ?", Integer.class, productId);
        assertThat(quantity).isEqualTo(2);
    }

    @Test
    @DisplayName("#2 재고 row 미존재 — 재시도를 거쳐 격리되고, 뒤따르는 정상 메시지는 계속 처리된다")
    void rowNotFound_retriesThenQuarantines_subsequentMessageStillProcessed() {
        long missingProductId = 9002L;
        String orderId1 = "order-notfound-" + UUID.randomUUID();
        String idempotencyKey1 = UUID.randomUUID().toString();
        // productId 9002 는 의도적으로 시드하지 않는다 — commitToRdb 가 IllegalStateException 을 던진다

        long healthyProductId = 9003L;
        String orderId2 = "order-followup-" + UUID.randomUUID();
        String idempotencyKey2 = UUID.randomUUID().toString();
        seedProduct(healthyProductId, 10);

        String badPayload = buildPayload(missingProductId, 1, idempotencyKey1, orderId1);
        stockCommittedQuarantineKafkaTemplate.send(
                ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED, String.valueOf(missingProductId), badPayload);

        // 첫 메시지가 재시도를 소진하고 격리 토픽에 도달할 때까지 대기 — 그 사이 두 번째 메시지는 아직 보내지 않는다
        List<ConsumerRecord<String, String>> quarantined1 = pollQuarantine(orderId1, Duration.ofSeconds(10));
        assertThat(quarantined1).hasSize(1);
        assertThat(quarantined1.get(0).key()).isEqualTo(orderId1 + ":" + missingProductId);

        // 단일 시도가 아니라 반복 실패했다 — retryable 목록에 없어 재시도 경로를 탄 증거
        verify(stockCommitUseCase, atLeast(2))
                .commit(eq(idempotencyKey1), eq(orderId1), eq(missingProductId), eq(1), any(), any());

        String goodPayload = buildPayload(healthyProductId, 3, idempotencyKey2, orderId2);
        stockCommittedQuarantineKafkaTemplate.send(
                ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED, String.valueOf(healthyProductId), goodPayload);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer quantity = jdbcTemplate.queryForObject(
                    "SELECT quantity FROM stock WHERE product_id = ?", Integer.class, healthyProductId);
            assertThat(quantity).isEqualTo(7);
        });

        // 뒤따르는 메시지는 격리로 가지 않는다
        List<ConsumerRecord<String, String>> quarantined2 = pollQuarantine(orderId2, Duration.ofSeconds(2));
        assertThat(quarantined2).isEmpty();
    }

    private void seedProduct(long productId, int quantity) {
        jdbcTemplate.update(
                "INSERT INTO product (id, name, price, description, seller_id) VALUES (?, ?, ?, ?, ?)",
                productId, "quarantine-test-product", 1000, "quarantine test", 1L);
        jdbcTemplate.update(
                "INSERT INTO stock (product_id, quantity) VALUES (?, ?)", productId, quantity);
    }

    private String buildPayload(long productId, int qty, String idempotencyKey, String orderId) {
        Instant occurredAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant expiresAt = occurredAt.plus(Duration.ofDays(8));
        return "{\"productId\":" + productId + ",\"qty\":" + qty
                + ",\"idempotencyKey\":\"" + idempotencyKey + "\""
                + ",\"occurredAt\":\"" + occurredAt + "\""
                + ",\"orderId\":\"" + orderId + "\""
                + ",\"expiresAt\":\"" + expiresAt + "\"}";
    }

    /**
     * 격리 토픽을 폴링해 지정 orderId 에 해당하는 레코드 목록(키 포함)을 반환한다.
     * 원본 페이로드 안의 {@code orderId} 필드로 필터링한다 — 격리 메시지의 Kafka 키는
     * {@code orderId:productId} 조합으로 바뀌어 있어 orderId 단독 키 매칭이 불가능하다.
     */
    private List<ConsumerRecord<String, String>> pollQuarantine(String filterOrderId, Duration timeout) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-quarantine-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"
        );

        List<ConsumerRecord<String, String>> result = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED_DLQ));
            long deadline = System.currentTimeMillis() + timeout.toMillis();

            while (System.currentTimeMillis() < deadline && result.isEmpty()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                records.forEach(record -> {
                    if (record.value() != null && record.value().contains("\"orderId\":\"" + filterOrderId + "\"")) {
                        result.add(record);
                    }
                });
            }
        }
        return result;
    }
}
