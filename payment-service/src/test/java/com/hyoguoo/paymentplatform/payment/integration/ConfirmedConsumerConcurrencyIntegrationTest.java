package com.hyoguoo.paymentplatform.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.dto.event.StockCommittedEvent;
import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordSnapshot;
import com.hyoguoo.paymentplatform.payment.application.util.StockEventUuidDeriver;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.StockHoldRecordStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentOrderRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaStockHoldRecordRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/** Three partitions with three real listener children; all assertions use distinct order IDs. */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@EmbeddedKafka(partitions = 3, topics = {
        PaymentTopics.EVENTS_CONFIRMED,
        PaymentTopics.EVENTS_CONFIRMED_DLQ,
        PaymentTopics.EVENTS_STOCK_COMMITTED
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DisplayName("confirmed consumer 동시성 3 회귀")
class ConfirmedConsumerConcurrencyIntegrationTest {

    private static final long PRODUCT_ID = 100L;
    private static final int QUANTITY = 2;
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(10000);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("payment-concurrency-test").withUsername("test").withPassword("test")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
            .withReuse(true);

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379).withReuse(true);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
        registry.add("payment.cache.stock-redis.host", REDIS::getHost);
        registry.add("payment.cache.stock-redis.port", () -> String.valueOf(REDIS.getMappedPort(6379)));
        registry.add("scheduler.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
        registry.add("payment.kafka.events-confirmed.consumer.concurrency", () -> "3");
        registry.add("payment.kafka.error-handler.backoff.interval", () -> "200");
        registry.add("payment.kafka.error-handler.backoff.max-attempts", () -> "5");
        registry.add("payment.kafka.after-rollback.backoff.interval", () -> "200");
    }

    @Autowired
    @Qualifier("confirmedKafkaTemplate")
    private KafkaTemplate<String, String> input;

    @Autowired
    private JpaPaymentEventRepository events;

    @Autowired
    private JpaPaymentOrderRepository orders;

    @Autowired
    private JpaStockHoldRecordRepository holds;

    @Autowired
    private StockHoldRecordRepository holdRepository;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private org.springframework.kafka.test.EmbeddedKafkaBroker broker;

    @Autowired
    @Qualifier("stockCommittedProducerFactory")
    private ProducerFactory<String, String> producerFactory;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private TargetOrderCommitFailure activeFailure;

    @BeforeEach
    void setUp() {
        awaitThreeAssignments();
        clearRows();
    }

    @AfterEach
    void tearDown() {
        if (activeFailure != null) {
            activeFailure.releaseCommit.countDown();
            producerFactory.removePostProcessor(activeFailure);
            producerFactory.reset();
            activeFailure = null;
        }
        for (MessageListenerContainer listener : listeners.getListenerContainers()) {
            if (!listener.isRunning()) {
                listener.start();
            }
        }
        clearRows();
    }

    @Test
    @DisplayName("각 child에 한 파티션 배정, 같은 상품 독립 주문과 중복 재배달")
    void threeChildrenProcessIndependentOrdersAndRedelivery() throws Exception {
        List<Case> cases = cases();
        Map<TopicPartition, Long> lastOffsets = new HashMap<>();
        for (int partition = 0; partition < 3; partition++) {
            Case current = cases.get(partition);
            send(partition, current, lastOffsets);
            send(partition, current, lastOffsets);
        }
        awaitCommitted(lastOffsets);
        assertDoneAndHolds(cases);
        // DONE 종결 가드는 중복 입력에 같은 결정적 키로 다시 발행한다.
        // 실제 재고 차감의 1회성은 product-service의 키 멱등 처리 대상이다.
        assertStockEvents(cases, 2);
    }

    @Test
    @DisplayName("대상 주문 EOS 커밋 1회 실패 후 종결 가드 재발행"
            + " 및 다른 파티션 정상 처리")
    void targetedCommitFailureRecoversWithoutAffectingOtherPartitions() throws Exception {
        List<Case> cases = cases();
        seedFailure(cases.get(0).orderId(), 1);
        Map<TopicPartition, Long> lastOffsets = sendAll(cases);
        awaitCommitted(lastOffsets);
        assertThat(activeFailure.failures()).isEqualTo(1);
        assertDoneAndHolds(cases);
        assertStockEvents(cases, 1);
    }

    @Test
    @DisplayName("대상 주문 EOS 커밋 지속 실패는 DLQ에 격리,"
            + " 다른 파티션은 정상이며 재고 확정은 유실")
    void targetedPersistentFailureReachesDlq() throws Exception {
        List<Case> cases = cases();
        seedFailure(cases.get(0).orderId(), 6);
        Map<TopicPartition, Long> lastOffsets = sendAll(cases);
        String failedOrderId = cases.get(0).orderId();
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(readTopic(PaymentTopics.EVENTS_CONFIRMED_DLQ,
                        Set.of(failedOrderId)).getOrDefault(failedOrderId, List.of()))
                        .hasSize(1));
        List<String> dlqMessages = readTopic(PaymentTopics.EVENTS_CONFIRMED_DLQ,
                Set.of(failedOrderId)).getOrDefault(failedOrderId, List.of());
        assertThat(dlqMessages).hasSize(1);
        ConfirmedEventMessage dlqMessage = mapper.readValue(dlqMessages.get(0), ConfirmedEventMessage.class);
        assertThat(dlqMessage.orderId()).isEqualTo(failedOrderId);
        assertThat(dlqMessage.eventUuid()).isEqualTo(cases.get(0).eventUuid());
        TopicPartition healthyOne = new TopicPartition(PaymentTopics.EVENTS_CONFIRMED, 1);
        TopicPartition healthyTwo = new TopicPartition(PaymentTopics.EVENTS_CONFIRMED, 2);
        awaitCommitted(Map.of(healthyOne, lastOffsets.get(healthyOne),
                healthyTwo, lastOffsets.get(healthyTwo)));
        assertThat(activeFailure.failures()).isEqualTo(6);
        assertDoneAndHolds(cases);
        assertStockEvents(cases.subList(1, 3), 1);
        assertStockEvents(cases.subList(0, 1), 0);

        // DLQ 도달만으로 실패 파티션의 offset commit을 가정하지 않는다.
        // 같은 파티션의 후속 정상 메시지가 진행되면 해당 offset까지 수렴한다.
        Case followup = newCase(0);
        send(0, followup, lastOffsets);
        awaitCommitted(lastOffsets);
        assertDoneAndHolds(List.of(followup));
        assertStockEvents(List.of(followup), 1);
        assertStockEvents(cases.subList(0, 1), 0);
    }

    @Test
    @DisplayName("커밋 실패 직전 리스너 중지 후 미커밋 입력을 재시작으로 재배달")
    void uncommittedDeliveryIsRecoveredAfterListenerRestart() throws Exception {
        List<Case> cases = cases();
        seedFailure(cases.get(0).orderId(), 1, true);
        ConcurrentMessageListenerContainer<?, ?> listener = confirmedListener();
        Map<TopicPartition, Long> lastOffsets = sendAll(cases);
        await().atMost(TIMEOUT).until(() -> activeFailure.commitEntered.getCount() == 0);
        await().atMost(TIMEOUT).untilAsserted(() -> assertDoneAndHolds(cases.subList(1, 3)));
        CountDownLatch stopped = new CountDownLatch(1);
        try {
            listener.stop(stopped::countDown);
        } finally {
            activeFailure.releaseCommit.countDown();
        }
        assertThat(stopped.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(activeFailure.failures()).isEqualTo(1);
        assertDoneAndHolds(cases);
        assertStockEvents(cases.subList(0, 1), 0);
        assertStockEvents(cases.subList(1, 3), 1);
        listener.start();
        awaitThreeAssignments();
        awaitCommitted(lastOffsets);
        assertDoneAndHolds(cases);
        assertStockEvents(cases, 1);
    }

    private List<Case> cases() {
        List<Case> result = new ArrayList<>();
        for (int partition = 0; partition < 3; partition++) {
            result.add(newCase(partition));
        }
        return result;
    }

    private Case newCase(int partition) {
        String orderId = "concurrency-" + partition + "-" + UUID.randomUUID();
        String eventUuid = UUID.randomUUID().toString();
        PaymentEventEntity event = events.save(PaymentEventEntity.builder()
                .buyerId(1L).sellerId(2L).orderName("동시성 회귀 " + orderId)
                .orderId(orderId).paymentKey("pay-key-" + orderId)
                .gatewayType(PaymentGatewayType.TOSS).status(PaymentEventStatus.IN_PROGRESS)
                .lastStatusChangedAt(Instant.now()).build());
        orders.save(PaymentOrderEntity.builder().paymentEventId(event.getId())
                .orderId(orderId).productId(PRODUCT_ID).quantity(QUANTITY)
                .totalAmount(AMOUNT).status(PaymentOrderStatus.EXECUTING).build());
        holdRepository.openHold(orderId, PaymentOrder.allArgsBuilder()
                .orderId(orderId).productId(PRODUCT_ID).quantity(QUANTITY)
                .totalAmount(AMOUNT).allArgsBuild());
        return new Case(orderId, eventUuid);
    }

    private Map<TopicPartition, Long> sendAll(List<Case> cases) throws Exception {
        Map<TopicPartition, Long> offsets = new HashMap<>();
        for (int partition = 0; partition < cases.size(); partition++) {
            send(partition, cases.get(partition), offsets);
        }
        return offsets;
    }

    private void send(int partition, Case current, Map<TopicPartition, Long> offsets) throws Exception {
        ConfirmedEventMessage message = new ConfirmedEventMessage(current.orderId(), "APPROVED", null,
                AMOUNT.longValue(), OffsetDateTime.now(ZoneOffset.UTC).toString(), current.eventUuid());
        long offset = input.send(new ProducerRecord<>(PaymentTopics.EVENTS_CONFIRMED, partition,
                current.orderId(), mapper.writeValueAsString(message))).get().getRecordMetadata().offset();
        offsets.put(new TopicPartition(PaymentTopics.EVENTS_CONFIRMED, partition), offset);
    }

    private void awaitThreeAssignments() {
        await().atMost(TIMEOUT).untilAsserted(() -> {
            ConcurrentMessageListenerContainer<?, ?> listener = confirmedListener();
            assertThat(listener.getConcurrency()).isEqualTo(3);
            assertThat(listener.getContainers()).hasSize(3);
            Set<TopicPartition> union = new HashSet<>();
            for (MessageListenerContainer child : listener.getContainers()) {
                Collection<TopicPartition> assigned = child.getAssignedPartitions();
                assertThat(assigned).hasSize(1);
                union.addAll(assigned);
            }
            assertThat(union).containsExactlyInAnyOrder(
                    new TopicPartition(PaymentTopics.EVENTS_CONFIRMED, 0),
                    new TopicPartition(PaymentTopics.EVENTS_CONFIRMED, 1),
                    new TopicPartition(PaymentTopics.EVENTS_CONFIRMED, 2));
        });
    }

    private ConcurrentMessageListenerContainer<?, ?> confirmedListener() {
        return listeners.getListenerContainers().stream()
                .filter(container -> container instanceof ConcurrentMessageListenerContainer)
                .map(container -> (ConcurrentMessageListenerContainer<?, ?>) container)
                .filter(container -> container.getContainerProperties().getTopics() != null
                        && List.of(container.getContainerProperties().getTopics())
                                .contains(PaymentTopics.EVENTS_CONFIRMED))
                .findFirst().orElseThrow();
    }

    private void awaitCommitted(Map<TopicPartition, Long> lastOffsets) {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            await().atMost(TIMEOUT).untilAsserted(() -> {
                Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committed =
                        admin.listConsumerGroupOffsets("payment-service").partitionsToOffsetAndMetadata().get();
                lastOffsets.forEach((partition, offset) -> {
                    assertThat(committed).containsKey(partition);
                    assertThat(committed.get(partition).offset()).isGreaterThan(offset);
                });
            });
        }
    }

    private void assertDoneAndHolds(List<Case> cases) {
        for (Case current : cases) {
            PaymentEventEntity event = events.findByOrderId(current.orderId()).orElseThrow();
            assertThat(event.getStatus())
                    .isEqualTo(PaymentEventStatus.DONE);
            assertThat(orders.findByPaymentEventId(event.getId()))
                    .singleElement()
                    .extracting(PaymentOrderEntity::getStatus)
                    .isEqualTo(PaymentOrderStatus.SUCCESS);
            assertThat(dedupeCount(current.eventUuid())).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM payment_history WHERE order_id = :orderId AND current_status = 'DONE'",
                    Map.of("orderId", current.orderId()), Integer.class)).isEqualTo(1);
            StockHoldRecordStatus status = holdRepository.findSnapshot(current.orderId(),
                    PaymentOrder.allArgsBuilder().orderId(current.orderId())
                            .productId(PRODUCT_ID).allArgsBuild())
                    .map(StockHoldRecordSnapshot::status).orElseThrow();
            assertThat(status).isEqualTo(StockHoldRecordStatus.COMMITTED);
        }
    }

    private void assertStockEvents(List<Case> cases, int expectedPerOrder) throws Exception {
        Set<String> orderIds = new HashSet<>();
        cases.forEach(current -> orderIds.add(current.orderId()));
        Map<String, List<String>> payloads = readTopic(PaymentTopics.EVENTS_STOCK_COMMITTED, orderIds);
        for (Case current : cases) {
            List<String> values = payloads.getOrDefault(current.orderId(), List.of());
            assertThat(values).hasSize(expectedPerOrder);
            for (String json : values) {
                StockCommittedEvent stock = mapper.readValue(json, StockCommittedEvent.class);
                assertThat(stock.productId()).isEqualTo(PRODUCT_ID);
                assertThat(stock.qty()).isEqualTo(QUANTITY);
                assertThat(stock.idempotencyKey()).isEqualTo(
                        StockEventUuidDeriver.derive(current.orderId(), PRODUCT_ID, "stock-commit"));
            }
        }
    }

    /** Read every partition through a snapshot end offset, including after the expected result appears. */
    private Map<String, List<String>> readTopic(String topic, Set<String> orderIds) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "concurrency-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        Map<String, List<String>> result = new HashMap<>();
        try (KafkaConsumer<String, String> reader = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = List.of(new TopicPartition(topic, 0),
                    new TopicPartition(topic, 1), new TopicPartition(topic, 2));
            reader.assign(partitions);
            reader.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = reader.endOffsets(partitions);
            await().atMost(TIMEOUT).until(() -> {
                reader.poll(Duration.ofMillis(200)).forEach(record -> {
                    String orderId = record.key();
                    if (PaymentTopics.EVENTS_STOCK_COMMITTED.equals(topic)) {
                        try {
                            orderId = mapper.readValue(record.value(), StockCommittedEvent.class).orderId();
                        } catch (Exception e) {
                            throw new IllegalStateException("stock-committed 역직렬화 실패", e);
                        }
                    }
                    if (orderIds.contains(orderId)) {
                        result.computeIfAbsent(orderId, ignored -> new ArrayList<>()).add(record.value());
                    }
                });
                return partitions.stream().allMatch(partition -> reader.position(partition) >= end.get(partition));
            });
        }
        return result;
    }

    private int dedupeCount(String uuid) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM payment_event_dedupe WHERE event_uuid = :uuid",
                Map.of("uuid", uuid), Integer.class);
        return count == null ? 0 : count;
    }

    private void clearRows() {
        orders.deleteAllInBatch();
        events.deleteAllInBatch();
        holds.deleteAllInBatch();
        jdbc.update("DELETE FROM payment_event_dedupe", Map.of());
    }

    private void seedFailure(String orderId, int count) {
        seedFailure(orderId, count, false);
    }

    private void seedFailure(String orderId, int count, boolean pauseBeforeFailure) {
        activeFailure = new TargetOrderCommitFailure(orderId, count, pauseBeforeFailure);
        producerFactory.addPostProcessor(activeFailure);
        producerFactory.reset();
    }

    private record Case(String orderId, String eventUuid) { }

    /** Per-producer target marker: commits from the other two listener children are never failed. */
    private static final class TargetOrderCommitFailure
            implements org.springframework.kafka.core.ProducerPostProcessor<String, String> {
        private final String orderId;
        private final AtomicInteger remaining;
        private final AtomicInteger failures = new AtomicInteger();
        private final CountDownLatch commitEntered = new CountDownLatch(1);
        private final CountDownLatch releaseCommit = new CountDownLatch(1);
        private final boolean pauseBeforeFailure;

        private TargetOrderCommitFailure(String orderId, int count, boolean pauseBeforeFailure) {
            this.orderId = orderId;
            this.remaining = new AtomicInteger(count);
            this.pauseBeforeFailure = pauseBeforeFailure;
        }

        int failures() {
            return failures.get();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Producer<String, String> apply(Producer<String, String> delegate) {
            AtomicBoolean targetTransaction = new AtomicBoolean();
            return (Producer<String, String>) Proxy.newProxyInstance(Producer.class.getClassLoader(),
                    new Class<?>[]{Producer.class}, (proxy, method, args) -> {
                        if ("beginTransaction".equals(method.getName())) {
                            targetTransaction.set(false);
                        }
                        if ("send".equals(method.getName()) && args != null && args.length > 0
                                && args[0] instanceof ProducerRecord<?, ?> record
                                && PaymentTopics.EVENTS_STOCK_COMMITTED.equals(record.topic())
                                && record.value() instanceof String payload
                                && payload.contains("\"orderId\":\"" + orderId + "\"")) {
                            targetTransaction.set(true);
                        }
                        if ("commitTransaction".equals(method.getName()) && targetTransaction.get()
                                && remaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                            if (pauseBeforeFailure) {
                                commitEntered.countDown();
                                if (!releaseCommit.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                                    throw new IllegalStateException("EOS commit failure latch timed out");
                                }
                            }
                            failures.incrementAndGet();
                            throw new KafkaException("대상 주문 EOS commit 실패 주입: " + orderId);
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }
}
