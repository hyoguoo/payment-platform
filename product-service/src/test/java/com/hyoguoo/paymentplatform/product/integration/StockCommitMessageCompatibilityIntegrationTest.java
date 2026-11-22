package com.hyoguoo.paymentplatform.product.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hyoguoo.paymentplatform.product.application.usecase.StockCommitUseCase;
import com.hyoguoo.paymentplatform.product.exception.ProductStockException;
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.ProductTopics;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
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
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;

/**
 * 재고 확정 메시지의 필드 누락·null·모르는 필드·빈 값 레코드를 실제 경로(Boot ObjectMapper 변환기, MySQL 중복 판정
 * 저장소, 트랜잭션 프록시, 에러 핸들러)로 흘려 지금 동작을 고정하는 특성 테스트.
 *
 * <p>정상 발행 경로는 6개 필드를 모두 채우므로 기존 테스트는 이 경로를 지나지 않는다. 메시지 DTO 의 선언이나
 * 변환기 설정이 바뀌면 같은 메시지가 격리되거나 조용히 사라질 수 있어, 시나리오마다 유스케이스 호출 횟수,
 * 격리 토픽 도달과 키, 재고, 중복 판정 행을 함께 단언한다.
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
@DisplayName("재고 확정 메시지 호환 특성 통합 검증")
class StockCommitMessageCompatibilityIntegrationTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration DEDUPE_TTL = Duration.ofDays(8);
    private static final Duration QUARANTINE_WAIT = Duration.ofSeconds(10);
    private static final Duration ABSENCE_WAIT = Duration.ofSeconds(3);

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("product-compat-test")
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
        registry.add("spring.flyway.locations", () -> "classpath:db/schema");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("scheduler.enabled", () -> "false");
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
    @DisplayName("FS-3 재고 행 없음 — 재시도 뒤 격리되고 중복 판정 행이 남지 않는다")
    void rowNotFound_rollsBackDedupeRecord() {
        long productId = 9101L;
        String orderId = "order-fs3-missing-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        send(productId, fullPayload(productId, 1, idempotencyKey, orderId));

        List<ConsumerRecord<String, String>> quarantined = pollQuarantine(byOrderId(orderId), QUARANTINE_WAIT);
        assertThat(quarantined).hasSize(1);
        assertThat(causeOf(quarantined.get(0))).isEqualTo(IllegalStateException.class.getName());
        assertThat(countDedupe(idempotencyKey)).isZero();
    }

    @Test
    @DisplayName("FS-3 재고 부족 — 격리되고 중복 판정 행이 남지 않는다")
    void insufficientStock_rollsBackDedupeRecord() {
        long productId = 9102L;
        String orderId = "order-fs3-insufficient-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        seedProduct(productId, 2);

        send(productId, fullPayload(productId, 5, idempotencyKey, orderId));

        List<ConsumerRecord<String, String>> quarantined = pollQuarantine(byOrderId(orderId), QUARANTINE_WAIT);
        assertThat(quarantined).hasSize(1);
        assertThat(causeOf(quarantined.get(0))).isEqualTo(ProductStockException.class.getName());
        assertThat(countDedupe(idempotencyKey)).isZero();
        assertThat(stockOf(productId)).isEqualTo(2);
    }

    @Test
    @DisplayName("FS-4 occurredAt·expiresAt·orderId 가 null — 차감되고 만료 시각은 수신 시각 + 8일, orderId 는 빈 문자열")
    void nullOptionalFields_areFilledWithFallbacks() {
        long productId = 9103L;
        String idempotencyKey = UUID.randomUUID().toString();
        seedProduct(productId, 10);

        Instant before = Instant.now();
        send(productId, "{\"productId\":" + productId + ",\"qty\":3,\"idempotencyKey\":\"" + idempotencyKey
                + "\",\"occurredAt\":null,\"orderId\":null,\"expiresAt\":null}");

        awaitStock(productId, 7);
        Instant after = Instant.now();
        verify(stockCommitUseCase, times(1))
                .commit(eq(idempotencyKey), eq(""), eq(productId), eq(3), any(), any());
        assertThat(expiresAtOf(idempotencyKey))
                .isBetween(before.plus(DEDUPE_TTL).minusSeconds(1), after.plus(DEDUPE_TTL).plusSeconds(1));
    }

    @Test
    @DisplayName("FS-4 expiresAt·orderId 가 빠짐 — 차감되고 만료 시각은 occurredAt + 8일")
    void absentOptionalFields_useOccurredAtForExpiry() {
        long productId = 9104L;
        String idempotencyKey = UUID.randomUUID().toString();
        seedProduct(productId, 10);

        send(productId, "{\"productId\":" + productId + ",\"qty\":4,\"idempotencyKey\":\"" + idempotencyKey
                + "\",\"occurredAt\":\"" + OCCURRED_AT + "\"}");

        awaitStock(productId, 6);
        verify(stockCommitUseCase, times(1))
                .commit(eq(idempotencyKey), eq(""), eq(productId), eq(4), any(), any());
        assertThat(expiresAtOf(idempotencyKey)).isEqualTo(OCCURRED_AT.plus(DEDUPE_TTL));
    }

    @Test
    @DisplayName("FS-5 productId 가 null — 유스케이스를 부르지 못하고 재시도 뒤 원본 키로 격리된다")
    void nullProductId_quarantinesWithOriginalKey() {
        String orderId = "order-fs5-null-" + UUID.randomUUID();
        String kafkaKey = "fs5-null-" + UUID.randomUUID();

        stockCommittedQuarantineKafkaTemplate.send(ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED, kafkaKey,
                "{\"productId\":null,\"qty\":1,\"idempotencyKey\":\"" + UUID.randomUUID()
                        + "\",\"occurredAt\":\"" + OCCURRED_AT + "\",\"orderId\":\"" + orderId
                        + "\",\"expiresAt\":\"" + OCCURRED_AT.plus(DEDUPE_TTL) + "\"}");

        List<ConsumerRecord<String, String>> quarantined = pollQuarantine(byOrderId(orderId), QUARANTINE_WAIT);
        assertThat(quarantined).hasSize(1);
        assertThat(quarantined.get(0).key()).isEqualTo(kafkaKey);
        assertThat(causeOf(quarantined.get(0))).isEqualTo(NullPointerException.class.getName());
        verify(stockCommitUseCase, never()).commit(any(), any(), anyLong(), anyInt(), any(), any());
        assertThat(countAllDedupe()).isZero();
    }

    @Test
    @DisplayName("FS-5 productId 가 빠짐 — 유스케이스를 부르지 못하고 재시도 뒤 원본 키로 격리된다")
    void absentProductId_quarantinesWithOriginalKey() {
        String orderId = "order-fs5-absent-" + UUID.randomUUID();
        String kafkaKey = "fs5-absent-" + UUID.randomUUID();

        stockCommittedQuarantineKafkaTemplate.send(ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED, kafkaKey,
                "{\"qty\":1,\"idempotencyKey\":\"" + UUID.randomUUID()
                        + "\",\"occurredAt\":\"" + OCCURRED_AT + "\",\"orderId\":\"" + orderId + "\"}");

        List<ConsumerRecord<String, String>> quarantined = pollQuarantine(byOrderId(orderId), QUARANTINE_WAIT);
        assertThat(quarantined).hasSize(1);
        assertThat(quarantined.get(0).key()).isEqualTo(kafkaKey);
        assertThat(causeOf(quarantined.get(0))).isEqualTo(NullPointerException.class.getName());
        verify(stockCommitUseCase, never()).commit(any(), any(), anyLong(), anyInt(), any(), any());
        assertThat(countAllDedupe()).isZero();
    }

    @Test
    @DisplayName("FS-5b qty 가 빠짐 — 0 으로 들어와 재고는 그대로이고 중복 판정 행만 남는다")
    void absentQty_isTreatedAsZero() {
        long productId = 9105L;
        String orderId = "order-fs5b-absent-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        seedProduct(productId, 10);

        send(productId, "{\"productId\":" + productId + ",\"idempotencyKey\":\"" + idempotencyKey
                + "\",\"occurredAt\":\"" + OCCURRED_AT + "\",\"orderId\":\"" + orderId + "\"}");

        await().atMost(QUARANTINE_WAIT).untilAsserted(() -> assertThat(countDedupe(idempotencyKey)).isEqualTo(1));
        verify(stockCommitUseCase, times(1))
                .commit(eq(idempotencyKey), eq(orderId), eq(productId), eq(0), any(), any());
        assertThat(stockOf(productId)).isEqualTo(10);
        assertThat(pollQuarantine(byOrderId(orderId), ABSENCE_WAIT)).isEmpty();
    }

    @Test
    @DisplayName("FS-5b qty 가 null — 0 으로 들어와 재고는 그대로이고 중복 판정 행만 남는다")
    void nullQty_isTreatedAsZero() {
        long productId = 9106L;
        String orderId = "order-fs5b-null-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        seedProduct(productId, 10);

        send(productId, "{\"productId\":" + productId + ",\"qty\":null,\"idempotencyKey\":\"" + idempotencyKey
                + "\",\"occurredAt\":\"" + OCCURRED_AT + "\",\"orderId\":\"" + orderId + "\"}");

        await().atMost(QUARANTINE_WAIT).untilAsserted(() -> assertThat(countDedupe(idempotencyKey)).isEqualTo(1));
        verify(stockCommitUseCase, times(1))
                .commit(eq(idempotencyKey), eq(orderId), eq(productId), eq(0), any(), any());
        assertThat(stockOf(productId)).isEqualTo(10);
        assertThat(pollQuarantine(byOrderId(orderId), ABSENCE_WAIT)).isEmpty();
    }

    @Test
    @DisplayName("FS-12 idempotencyKey 가 null 인 메시지 2건 — 첫 건만 차감되고 두 번째는 중복으로 무시된다")
    void nullIdempotencyKey_secondMessageIsTreatedAsDuplicate() {
        long productId = 9107L;
        String orderId1 = "order-fs12-first-" + UUID.randomUUID();
        String orderId2 = "order-fs12-second-" + UUID.randomUUID();
        seedProduct(productId, 10);

        send(productId, payloadWithKeyLiteral(productId, 2, "null", orderId1));
        awaitStock(productId, 8);
        send(productId, payloadWithKeyLiteral(productId, 3, "null", orderId2));

        await().atMost(QUARANTINE_WAIT).untilAsserted(() ->
                verify(stockCommitUseCase, times(1))
                        .commit(eq(null), eq(orderId2), eq(productId), eq(3), any(), any()));
        assertThat(stockOf(productId)).isEqualTo(8);
        assertThat(jdbcTemplate.queryForList("SELECT event_uuid FROM stock_commit_dedupe", String.class))
                .containsExactly("");
        assertThat(pollQuarantine(byOrderId(orderId2), ABSENCE_WAIT)).isEmpty();
    }

    @Test
    @DisplayName("FS-12 idempotencyKey 가 빠진 메시지 — null 과 같이 빈 문자열 키로 기록되고 차감된다")
    void absentIdempotencyKey_isRecordedAsEmptyKey() {
        long productId = 9108L;
        String orderId = "order-fs12-absent-" + UUID.randomUUID();
        seedProduct(productId, 10);

        send(productId, "{\"productId\":" + productId + ",\"qty\":2"
                + ",\"occurredAt\":\"" + OCCURRED_AT + "\",\"orderId\":\"" + orderId + "\"}");

        awaitStock(productId, 8);
        verify(stockCommitUseCase, times(1))
                .commit(eq(null), eq(orderId), eq(productId), eq(2), any(), any());
        assertThat(jdbcTemplate.queryForList("SELECT event_uuid FROM stock_commit_dedupe", String.class))
                .containsExactly("");
    }

    @Test
    @DisplayName("FS-13 모르는 필드가 있는 메시지 — 무시하고 차감한다")
    void unknownField_isIgnored() {
        long productId = 9109L;
        String orderId = "order-fs13-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        seedProduct(productId, 10);

        String payload = fullPayload(productId, 4, idempotencyKey, orderId);
        send(productId, payload.substring(0, payload.length() - 1) + ",\"newField\":{\"nested\":true}}");

        awaitStock(productId, 6);
        verify(stockCommitUseCase, times(1))
                .commit(eq(idempotencyKey), eq(orderId), eq(productId), eq(4), any(), any());
        assertThat(countDedupe(idempotencyKey)).isEqualTo(1);
    }

    @Test
    @DisplayName("FS-14 값이 null 인 레코드 — 유스케이스를 부르지 않고 원본 키로 격리된다")
    void tombstone_quarantinesWithOriginalKey() {
        String kafkaKey = "fs14-tombstone-" + UUID.randomUUID();

        stockCommittedQuarantineKafkaTemplate.send(ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED, kafkaKey, null);

        List<ConsumerRecord<String, String>> quarantined =
                pollQuarantine(rec -> kafkaKey.equals(rec.key()), QUARANTINE_WAIT);
        assertThat(quarantined).hasSize(1);
        assertThat(quarantined.get(0).value()).isNull();
        // 리스너 파라미터가 필수 본문이라 Spring 이 값 없음으로 거부한다. 파라미터를 nullable 로 바꾸면 null 이 넘어간다.
        assertThat(causeOf(quarantined.get(0))).isEqualTo(MethodArgumentNotValidException.class.getName());
        verify(stockCommitUseCase, never()).commit(any(), any(), anyLong(), anyInt(), any(), any());
        assertThat(countAllDedupe()).isZero();
    }

    private void send(long productId, String payload) {
        stockCommittedQuarantineKafkaTemplate.send(
                ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED, String.valueOf(productId), payload);
    }

    private String fullPayload(long productId, int qty, String idempotencyKey, String orderId) {
        return payloadWithKeyLiteral(productId, qty, "\"" + idempotencyKey + "\"", orderId);
    }

    private String payloadWithKeyLiteral(long productId, int qty, String idempotencyKeyLiteral, String orderId) {
        return "{\"productId\":" + productId + ",\"qty\":" + qty
                + ",\"idempotencyKey\":" + idempotencyKeyLiteral
                + ",\"occurredAt\":\"" + OCCURRED_AT + "\""
                + ",\"orderId\":\"" + orderId + "\""
                + ",\"expiresAt\":\"" + OCCURRED_AT.plus(DEDUPE_TTL) + "\"}";
    }

    private void seedProduct(long productId, int quantity) {
        jdbcTemplate.update(
                "INSERT INTO product (id, name, price, description, seller_id) VALUES (?, ?, ?, ?, ?)",
                productId, "compat-test-product", 1000, "compat test", 1L);
        jdbcTemplate.update("INSERT INTO stock (product_id, quantity) VALUES (?, ?)", productId, quantity);
    }

    private void awaitStock(long productId, int expected) {
        await().atMost(QUARANTINE_WAIT).untilAsserted(() -> assertThat(stockOf(productId)).isEqualTo(expected));
    }

    private Integer stockOf(long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM stock WHERE product_id = ?", Integer.class, productId);
    }

    private Integer countDedupe(String idempotencyKey) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_commit_dedupe WHERE event_uuid = ?", Integer.class, idempotencyKey);
    }

    private Integer countAllDedupe() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_commit_dedupe", Integer.class);
    }

    /**
     * 저장소({@code JdbcEventDedupeStore})가 UTC 달력으로 바인딩해 쓰므로 같은 달력으로 읽는다.
     * 달력 없이 읽으면 JVM 기본 시간대만큼 어긋난다.
     */
    private Instant expiresAtOf(String idempotencyKey) {
        Timestamp expiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM stock_commit_dedupe WHERE event_uuid = ?",
                (rs, rowNum) -> rs.getTimestamp("expires_at", Calendar.getInstance(TimeZone.getTimeZone("UTC"))),
                idempotencyKey);
        assertThat(expiresAt).isNotNull();
        return expiresAt.toInstant();
    }

    /**
     * 격리 메시지에 실린 실패 원인 예외 클래스. 재시도 여부는 이 클래스와 {@code KafkaErrorHandlerConfig} 의
     * 재시도 제외 목록으로 정해지므로, 원인 클래스를 고정하면 재시도 분류도 고정된다.
     */
    private static String causeOf(ConsumerRecord<String, String> rec) {
        Header header = rec.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
        assertThat(header).as("격리 메시지에 원인 예외 헤더가 없다").isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static Predicate<ConsumerRecord<String, String>> byOrderId(String orderId) {
        return rec -> rec.value() != null && rec.value().contains("\"orderId\":\"" + orderId + "\"");
    }

    private List<ConsumerRecord<String, String>> pollQuarantine(
            Predicate<ConsumerRecord<String, String>> filter, Duration timeout) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-compat-reader-" + UUID.randomUUID(),
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
                records.forEach(rec -> {
                    if (filter.test(rec)) {
                        result.add(rec);
                    }
                });
            }
        }
        return result;
    }
}
