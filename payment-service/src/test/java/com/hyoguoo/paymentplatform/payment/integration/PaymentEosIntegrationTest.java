package com.hyoguoo.paymentplatform.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.dto.event.StockCommittedEvent;
import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventDedupeStore;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordSnapshot;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.util.StockEventUuidDeriver;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
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
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * EOS 통합 회귀 가드 7 시나리오.
 *
 * <p>검증 범위: EOS SUT 의 통합 동작.
 * ConfirmedEventConsumer → KafkaTransactionManager (EOS) → PaymentConfirmResultUseCase → RDB + Kafka 발행.
 *
 * <p>7 시나리오:
 * <ol>
 *   <li>#1 정상 EOS commit — APPROVED → dedupe 1 row + payment DONE + stock-committed 1건 가시화</li>
 *   <li>#2 abort 흐름 — RuntimeException → EOS abort → dedupe 0 row + payment 불변 + stock-committed 0건 + DLQ 1건</li>
 *   <li>#3 종결 가드 재발행 — 결제 DONE + dedupe row 기존(첫 처리 완료 모사) 상태에서 동일 event_uuid 재배달
 *       → 종결 가드가 DONE+APPROVED 를 재배달 신호로 보고 재발행 → stock-committed 1건 가시화 + payment DONE
 *       불변 + dedupe row 불변 + markIfAbsent 미재호출(발행 책임이 affected==0 분기에서 종결 가드로 이전됐음을 단정)</li>
 *   <li>#4 multi-product — PaymentOrder 2건 → stock-committed 2건 + productId 별 idempotencyKey 결정성(정상 경로만)</li>
 *   <li>#5 QUARANTINED 가드 — QUARANTINED 결제 + APPROVED → noop + dedupe 0 row + stock-committed 0건</li>
 *   <li>#6 결정적 EOS 커밋 실패 주입 — 복구(Task 3 시나리오 A): 1차 EOS 커밋 실패(주입) → stock-committed
 *       유실 + payment DONE + dedupe row 1건(JPA inner 동반 커밋 증거) → 재배달이 종결 가드로 재발행 →
 *       stock-committed 1건 복구(차감 정확히 1회)</li>
 *   <li>#7 결정적 EOS 커밋 실패 주입 — 갭 수정 검증(DLQ-REACHABILITY Task 4): 채택
 *       max-attempts(5) 소진 + 1회 결정적 실패 주입 → {@code KafkaConsumerConfig} 가 명시 연결한
 *       {@code DefaultAfterRollbackProcessor}(독립 backoff {@code payment.kafka.after-rollback.backoff.*}
 *       + {@code kafkaErrorHandler} 와 공유하는 {@code DeadLetterPublishingRecoverer})가 발화해
 *       {@code events.confirmed.dlq} 1건 도달(기존 "DLQ 미진입" 단정의 반전) + payment DONE 유지
 *       + dedupe row 1건 유지 + {@code PaymentEosCommitFailureMetrics} 증가. 단, 종결 가드 재발행도
 *       같은 EOS 트랜잭션 안에서 수행되므로 commitTransaction() 이 매번 실패하면 발행 자체가 매번
 *       abort 돼 stock-committed 는 여전히 0건(완전 유실, over-sell 잔여 위험은 설계 §미해결
 *       위험으로 그대로 유지 — 자동 복구는 범위 밖)</li>
 * </ol>
 *
 * <p>범위 밖 알려진 한계:
 * <ul>
 *   <li>abort invisibility 의 "stock-committed abort" 시뮬레이션 — stock-committed send 이후 abort 를
 *       주입할 수 없어, markPaymentAsDone RuntimeException 주입으로 대체 (발행 전 abort 검증)</li>
 *   <li>multi-instance transactional.id fencing — 통합 테스트 범위 밖</li>
 *   <li>EOS 커밋 실패 시 재고 확정 이벤트 자동 복구(#7) — DLQ 가시화까지만 다루고, DLQ 메시지
 *       재주입을 통한 stock-committed 복구는 범위 밖(후속 DLQ admin tool)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@EmbeddedKafka(
        partitions = 2,
        topics = {
                PaymentTopics.EVENTS_CONFIRMED,
                PaymentTopics.EVENTS_CONFIRMED_DLQ,
                PaymentTopics.EVENTS_STOCK_COMMITTED
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@DisplayName("EOS 통합 회귀 가드 7 시나리오")
class PaymentEosIntegrationTest {

    private static final Long PRODUCT_ID = 100L;
    private static final Long PRODUCT_ID_2 = 200L;
    private static final int ORDER_QUANTITY = 2;
    private static final BigDecimal UNIT_AMOUNT = BigDecimal.valueOf(10000);

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("payment-eos-test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand(
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_unicode_ci"
                    )
                    .withReuse(true);

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS_CONTAINER =
            new GenericContainer<>("redis:7.2-alpine")
                    .withExposedPorts(6379)
                    .withReuse(true);

    static {
        // @Testcontainers/@Container 를 사용하지 않고 수동 start.
        // @Container 로 관리하면 JUnit5 extension 이 테스트 클래스 완료 후 stop() 을 명시 호출하여
        // withReuse(true) 설정에도 불구하고 컨테이너가 종료된다.
        // 종료된 MySQL 컨테이너는 BaseIntegrationTest 컨텍스트(HikariPool) 의 연결을 끊어
        // 후속 PaymentControllerTest / PaymentCheckoutConcurrencyIntegrationTest 를 실패시킨다.
        MYSQL_CONTAINER.start();
        REDIS_CONTAINER.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // MySQL
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        // Flyway 활성화 — payment_event_dedupe V2 테이블 필요 (application-test.yml 에서 false 로 설정됨)
        registry.add("spring.flyway.enabled", () -> "true");
        // Flyway 와 JPA ddl-auto 충돌 방지 — Flyway 가 스키마를 담당
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // defer-datasource-initialization 비활성화 — Flyway 활성화 시 circular depends-on 방지
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
        // Redis (StockCacheRedisAdapter 빈 초기화용)
        registry.add("payment.cache.stock-redis.host", REDIS_CONTAINER::getHost);
        registry.add("payment.cache.stock-redis.port",
                () -> String.valueOf(REDIS_CONTAINER.getMappedPort(6379)));
        // 스케줄러 비활성화
        registry.add("scheduler.enabled", () -> "false");
        // KafkaListener auto-startup 활성화 (application-test.yml 에서 false 로 설정됨)
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
        // EOS backoff 단축 — 테스트 DLQ 검증 시간 단축 (backoff 1000ms × 5회 = 5s)
        registry.add("payment.kafka.error-handler.backoff.interval", () -> "200");
        registry.add("payment.kafka.error-handler.backoff.max-attempts", () -> "5");
        // AfterRollbackProcessor backoff 단축 — interval 만 단축(max-attempts 는 채택값 5 유지,
        // #7 시나리오가 채택 한도 소진을 실제 값 그대로 단정해야 하므로).
        registry.add("payment.kafka.after-rollback.backoff.interval", () -> "200");
    }

    @Autowired
    private KafkaTemplate<String, String> confirmedDlqKafkaTemplate;

    @Autowired
    private JpaPaymentEventRepository jpaPaymentEventRepository;

    @Autowired
    private JpaPaymentOrderRepository jpaPaymentOrderRepository;

    @Autowired
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Autowired
    private StockHoldRecordRepository stockHoldRecordRepository;

    @Autowired
    private JpaStockHoldRecordRepository jpaStockHoldRecordRepository;

    @MockitoSpyBean
    private StockCachePort stockCachePort;

    @MockitoSpyBean
    private PaymentCommandUseCase paymentCommandUseCase;

    @MockitoSpyBean
    private PaymentEventDedupeStore paymentEventDedupeStore;

    /** #9 전용 — 확정 표시 이후 실패를 주입해 같은 트랜잭션 롤백 동조를 검증한다. */
    @MockitoSpyBean(name = "stockCommittedKafkaTemplate")
    private KafkaTemplate<String, String> stockCommittedKafkaTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    @Qualifier("stockCommittedProducerFactory")
    private ProducerFactory<String, String> stockCommittedProducerFactory;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private String bootstrapServers;

    /**
     * #6·#7(Task 3) 전용 — 테스트 중 등록한 commitTransaction 실패 주입 postProcessor.
     * tearDown 에서 반드시 제거해 다른 시나리오(#1~#5)에 영향이 없도록 격리한다.
     */
    private CommitFailureInjectingProducerPostProcessor activeCommitFailurePostProcessor;

    @Autowired
    private org.springframework.kafka.test.EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @BeforeEach
    void setUp() {
        // cold-start 방어: consumer group join + partition assignment 가 setUp 안에서 완료되도록 대기.
        // ContainerTestUtils.waitForAssignment 은 파티션 수가 정확히 일치해야 하므로(strict equality),
        // 대신 Awaitility 로 "할당된 파티션 ≥ 1" 조건을 기다린다.
        // 이로써 #1 시나리오의 await(15초) 전에 consumer 가 준비 완료된다.
        for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> container.getAssignedPartitions() != null
                            && !container.getAssignedPartitions().isEmpty());
        }

        jpaPaymentOrderRepository.deleteAllInBatch();
        jpaPaymentEventRepository.deleteAllInBatch();
        jpaStockHoldRecordRepository.deleteAllInBatch();
        namedParameterJdbcTemplate.update(
                "DELETE FROM payment_event_dedupe",
                Collections.emptyMap()
        );
        bootstrapServers = embeddedKafkaBroker.getBrokersAsString();
    }

    @AfterEach
    void tearDown() {
        jpaPaymentOrderRepository.deleteAllInBatch();
        jpaPaymentEventRepository.deleteAllInBatch();
        jpaStockHoldRecordRepository.deleteAllInBatch();
        namedParameterJdbcTemplate.update(
                "DELETE FROM payment_event_dedupe",
                Collections.emptyMap()
        );
        // #6·#7(Task 3) 시드 격리 — 다음 테스트(다른 시나리오 포함)에 영향이 없도록 항상 제거한다.
        if (activeCommitFailurePostProcessor != null) {
            stockCommittedProducerFactory.removePostProcessor(activeCommitFailurePostProcessor);
            activeCommitFailurePostProcessor = null;
            // 시드 적용 중 생성된(프록시로 감싸진) 트랜잭션 producer 가 캐시에 남아 다음 테스트로
            // 재사용되는 것을 막기 위해 캐시를 비운다 — 다음 트랜잭션은 postProcessor 없는 producer 를 새로 생성한다.
            stockCommittedProducerFactory.reset();
        }
    }

    /**
     * #6·#7(Task 3) 전용 — stockCommittedProducerFactory 에 commitTransaction 실패 주입 시드를 등록한다.
     * 등록 직후 reset() 으로 기존 캐시된 producer 를 비워, 다음 트랜잭션이 시드 적용된 producer 를
     * 새로 생성하도록 강제한다(트랜잭션 producer 는 트랜잭션마다 캐시 재사용되므로 등록만으로는 적용되지 않을 수 있다).
     */
    private void seedCommitTransactionFailure(int failureCount) {
        activeCommitFailurePostProcessor = new CommitFailureInjectingProducerPostProcessor(failureCount);
        stockCommittedProducerFactory.addPostProcessor(activeCommitFailurePostProcessor);
        stockCommittedProducerFactory.reset();
    }

    // ── 시나리오 #1 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#1 정상 EOS commit: APPROVED 수신 → dedupe 1 row + payment DONE + stock-committed 1건 read_committed 가시화")
    void shouldCommitEosTransactionNormally() throws Exception {
        // given
        String orderId = "order-eos1-" + UUID.randomUUID();
        String eventUuid = UUID.randomUUID().toString();
        savePaymentInProgress(orderId, PRODUCT_ID, UNIT_AMOUNT);

        ConfirmedEventMessage message = approvedMessage(orderId, UNIT_AMOUNT.longValue(), eventUuid);
        String payload = objectMapper.writeValueAsString(message);

        // when
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — payment DONE 으로 전이
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId)
                            .orElseThrow();
                    assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.DONE);
                });

        // dedupe 1 row 존재
        int dedupeCount = countDedupeRow(eventUuid);
        assertThat(dedupeCount).isEqualTo(1);

        // stock-committed 1건 read_committed 가시화
        List<StockCommittedEvent> stockEvents = pollStockCommitted(orderId, 1, Duration.ofSeconds(10));
        assertThat(stockEvents).hasSize(1);
        assertThat(stockEvents.get(0).productId()).isEqualTo(PRODUCT_ID);
    }

    // ── 시나리오 #2 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#2 abort 흐름: RuntimeException 주입 → dedupe 0 row + payment 불변 + stock-committed 0건 + DLQ 1건")
    void shouldMakeAbortMessageInvisibleOnRollback() throws Exception {
        // given — markPaymentAsDone 에서 RuntimeException 주입 (handle 내 @Transactional 롤백 유발)
        String orderId = "order-eos2-" + UUID.randomUUID();
        String eventUuid = UUID.randomUUID().toString();
        savePaymentInProgress(orderId, PRODUCT_ID, UNIT_AMOUNT);

        doThrow(new RuntimeException("EOS abort 시뮬레이션 — markPaymentAsDone RuntimeException"))
                .when(paymentCommandUseCase).markPaymentAsDone(any(PaymentEvent.class), any(Instant.class));

        ConfirmedEventMessage message = approvedMessage(orderId, UNIT_AMOUNT.longValue(), eventUuid);
        String payload = objectMapper.writeValueAsString(message);

        // when
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — 5회 retry 후 DLQ (interval 200ms × 5 = 최소 1s, 여유 30s)
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() ->
                        verify(paymentCommandUseCase, times(6)).markPaymentAsDone(
                                any(PaymentEvent.class), any(Instant.class))
                );

        // dedupe 0 row — RDB 롤백 확인
        assertThat(countDedupeRow(eventUuid)).isZero();

        // payment 상태 불변 (IN_PROGRESS 유지)
        PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.IN_PROGRESS);

        // stock-committed 0건 (발행 전 abort 으로 인해 전송되지 않음)
        List<StockCommittedEvent> stockEvents = pollStockCommitted(orderId, 0, Duration.ofSeconds(3));
        assertThat(stockEvents).isEmpty();
    }

    // ── 시나리오 #3 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#3 종결 가드 재발행: 결제 DONE + dedupe row 기존(첫 처리 완료 모사) 상태에서 동일 event_uuid 재배달"
            + " → 종결 가드 재발행 → stock-committed 1건 가시화 + payment DONE 불변 + dedupe row 불변"
            + " + markIfAbsent 미재호출(발행 책임이 종결 가드로 이전됐음을 단정)")
    void shouldResendStockCommittedViaTerminalGuardOnRedelivery() throws Exception {
        // given — 결제는 이미 DONE(첫 처리 완료), dedupe row 도 이미 존재(첫 처리 시 커밋된 상태)
        // 그 상태에서 같은 event_uuid 의 APPROVED 메시지가 재배달되는 상황을 시뮬레이션
        // (실서비스: RDB DONE 커밋 후 Kafka 발행/오프셋 커밋이 유실되면 재배달이 일어난다)
        String orderId = "order-eos3-" + UUID.randomUUID();
        String eventUuid = UUID.randomUUID().toString();
        savePaymentDone(orderId, PRODUCT_ID, UNIT_AMOUNT);

        // 첫 처리 때 이미 커밋된 dedupe row — 재배달 시 markIfAbsent 가 호출된다면 0 row 를 반환할 상태
        insertDedupeRow(eventUuid, orderId, "APPROVED");

        // 카운터는 클래스 전체에서 공유되는 MeterRegistry 이므로 증가분으로 단정한다 (절대값 아님).
        double guardSkipBefore = getCounterValue("payment_confirm_guard_skip_total", "DONE");
        double terminalResendBefore = getCounterValue("payment_confirm_terminal_resend_total", "DONE");

        ConfirmedEventMessage message = approvedMessage(orderId, UNIT_AMOUNT.longValue(), eventUuid);
        String payload = objectMapper.writeValueAsString(message);

        // when — 종결(DONE) 상태에서 동일 event_uuid 의 APPROVED 재배달
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — 종결 가드가 DONE+APPROVED 를 재배달 신호로 보고 재발행 → stock-committed 1건 가시화
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    List<StockCommittedEvent> stockEvents = pollStockCommitted(orderId, 1, Duration.ofSeconds(5));
                    assertThat(stockEvents).hasSize(1);
                });

        // payment 상태 불변 (DONE 유지 — 종결 가드는 상태 전이를 하지 않는다)
        PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.DONE);

        // dedupe row 불변 (선행 삽입 row 1개 그대로 — 종결 가드는 dedupe 를 거치지 않는다)
        assertThat(countDedupeRow(eventUuid)).isEqualTo(1);

        // 발행 책임 이전 단정: 재발행이 종결 가드 경로로만 일어나고 affected==0 분기는 미진입했음을 확인
        // — markIfAbsent 가 재호출되지 않았다 (종결 가드가 markIfAbsent 호출 전에 먼저 가로챔)
        verify(paymentEventDedupeStore, times(0))
                .markIfAbsent(eq(eventUuid), any(Long.class), any(String.class), any(Instant.class));
        // — markPaymentAsDone 도 재호출되지 않았다 (이미 DONE 이므로 상태 전이 로직 미진입)
        verify(paymentCommandUseCase, times(0))
                .markPaymentAsDone(any(PaymentEvent.class), any(Instant.class));
        // — guardSkipMetrics(DONE) + terminalResendMetrics(DONE) 카운터가 함께 1만큼 증가했다
        assertThat(getCounterValue("payment_confirm_guard_skip_total", "DONE")).isEqualTo(guardSkipBefore + 1.0);
        assertThat(getCounterValue("payment_confirm_terminal_resend_total", "DONE"))
                .isEqualTo(terminalResendBefore + 1.0);
    }

    // ── 시나리오 #4 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#4 multi-product: PaymentOrder 2건 → stock-committed 2건 + productId 별 distinct idempotencyKey")
    void shouldPublishDistinctIdempotencyKeyPerProductOnMultiProduct() throws Exception {
        // given — 2개 ProductId 포함 PaymentEvent
        String orderId = "order-eos4-" + UUID.randomUUID();
        String eventUuid = UUID.randomUUID().toString();
        BigDecimal totalAmount = UNIT_AMOUNT.multiply(BigDecimal.valueOf(2));
        savePaymentInProgressMultiProduct(orderId, totalAmount);

        ConfirmedEventMessage message = approvedMessage(orderId, totalAmount.longValue(), eventUuid);
        String payload = objectMapper.writeValueAsString(message);

        // when
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — payment DONE 전이
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId)
                            .orElseThrow();
                    assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.DONE);
                });

        // stock-committed 2건 가시화
        List<StockCommittedEvent> stockEvents = pollStockCommitted(orderId, 2, Duration.ofSeconds(10));
        assertThat(stockEvents).hasSize(2);

        // productId 별 idempotencyKey 결정성 검증
        String expectedKey1 = StockEventUuidDeriver.derive(orderId, PRODUCT_ID, "stock-commit");
        String expectedKey2 = StockEventUuidDeriver.derive(orderId, PRODUCT_ID_2, "stock-commit");

        List<String> actualKeys = new ArrayList<>();
        for (StockCommittedEvent event : stockEvents) {
            actualKeys.add(event.idempotencyKey());
        }
        assertThat(actualKeys).containsExactlyInAnyOrder(expectedKey1, expectedKey2);
        // 두 키가 서로 다름 (productId 별 고유)
        assertThat(expectedKey1).isNotEqualTo(expectedKey2);

        // dedupe 1 row
        assertThat(countDedupeRow(eventUuid)).isEqualTo(1);
        // 재배달 흡수 시나리오는 #3(종결 가드 재발행)이 커버한다 — 본 시나리오는
        // 정상 경로 멀티상품 distinct idempotencyKey 결정성만 검증한다(회귀 가드 보존).
    }

    // ── 시나리오 #5 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("#5 QUARANTINED 가드: QUARANTINED 결제 + APPROVED → dedupe 0 row + stock-committed 0건 + DLQ 0건")
    void shouldSkipQuarantinedLateApprovedWithNoDlq() throws Exception {
        // given — QUARANTINED 상태의 결제 이벤트 저장
        String orderId = "order-eos5-" + UUID.randomUUID();
        String eventUuid = UUID.randomUUID().toString();
        savePaymentQuarantined(orderId, PRODUCT_ID, UNIT_AMOUNT);

        // 늦게 도착한 APPROVED 메시지
        ConfirmedEventMessage message = approvedMessage(orderId, UNIT_AMOUNT.longValue(), eventUuid);
        String payload = objectMapper.writeValueAsString(message);

        // when
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — 충분한 시간 대기 후 상태 불변 확인 (가드 → noop)
        // DLQ 발행이 없으므로 retry 를 기다리지 않고 2s 후 검증
        Thread.sleep(2000);

        // payment 상태 QUARANTINED 유지 (noop)
        PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.QUARANTINED);

        // dedupe 0 row (가드 → markIfAbsent 미호출)
        assertThat(countDedupeRow(eventUuid)).isZero();

        // stock-committed 0건
        List<StockCommittedEvent> stockEvents = pollStockCommitted(orderId, 0, Duration.ofSeconds(2));
        assertThat(stockEvents).isEmpty();

        // paymentCommandUseCase 호출 없음 (가드에서 early return)
        verify(paymentCommandUseCase, times(0)).markPaymentAsDone(
                any(PaymentEvent.class), any(Instant.class));
    }

    // ── 시나리오 #6 (Task 3 — 결정적 EOS 커밋 실패 주입: 복구) ──────────────────────────

    @Test
    @DisplayName("#6 결정적 EOS 커밋 실패 주입(복구): 1차 EOS 커밋 실패 → RDB DONE 커밋 + stock-committed 유실"
            + " → 재배달이 종결 가드로 재발행 → stock-committed 1건 복구(차감 정확히 1회)")
    void shouldRecoverStockCommittedAfterSingleEosCommitFailure() throws Exception {
        // given — READY(IN_PROGRESS) 결제 + 1차 EOS 커밋만 실패시키는 시드
        String orderId = "order-eos6-" + UUID.randomUUID();
        String eventUuid = UUID.randomUUID().toString();
        savePaymentInProgress(orderId, PRODUCT_ID, UNIT_AMOUNT);
        seedCommitTransactionFailure(1);

        ConfirmedEventMessage message = approvedMessage(orderId, UNIT_AMOUNT.longValue(), eventUuid);
        String payload = objectMapper.writeValueAsString(message);

        double terminalResendBefore = getCounterValue("payment_confirm_terminal_resend_total", "DONE");

        // when — 1차 배달: handle() 은 정상 진행(JPA DONE 커밋 + stock-committed buffer)되지만
        // 컨테이너의 EOS commitTransaction() 이 주입된 실패로 throw 되어 오프셋이 미커밋된다.
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — payment 는 DONE 으로 전이된다(JPA inner 커밋은 Kafka outer 커밋보다 먼저 일어나
        // EOS 커밋 실패와 무관하게 이미 RDB 에 반영됐다).
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId)
                            .orElseThrow();
                    assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.DONE);
                });

        // dedupe row 1건 — markIfAbsent 도 같은 JPA inner 트랜잭션으로 동반 커밋된 증거
        // (추후 종결 가드 앞으로 dedupe 가 이동하는 변경의 회귀 가드).
        assertThat(countDedupeRow(eventUuid)).isEqualTo(1);

        // 재배달이 종결 가드로 흡수되어 재발행 → stock-committed 1건이 결국 복구 가시화된다.
        List<StockCommittedEvent> stockEvents = pollStockCommitted(orderId, 1, Duration.ofSeconds(15));
        assertThat(stockEvents).hasSize(1);
        assertThat(stockEvents.get(0).productId()).isEqualTo(PRODUCT_ID);

        // 재배달이 종결 가드 분기로 진입했음을 단정 — markIfAbsent 는 1차 배달에서만 호출되고
        // 재배달(종결 가드)에서는 재호출되지 않는다(affected==0 분기 우회).
        verify(paymentEventDedupeStore, times(1))
                .markIfAbsent(eq(eventUuid), any(Long.class), any(String.class), any(Instant.class));
        // markPaymentAsDone 도 1차 배달에서만 호출된다(재배달은 이미 DONE 이므로 상태 전이 로직 미진입).
        verify(paymentCommandUseCase, times(1))
                .markPaymentAsDone(any(PaymentEvent.class), any(Instant.class));
        // terminalResendMetrics(DONE) 카운터가 재배달 1건만큼 증가했다.
        assertThat(getCounterValue("payment_confirm_terminal_resend_total", "DONE"))
                .isEqualTo(terminalResendBefore + 1.0);

        // 차감 정확히 1회 — 위 pollStockCommitted(orderId, 1, ...) 가 정확히 1건만 반환했다는 것
        // 자체가 중복 없음의 증거다(별도 group 의 추가 폴링은 동일 메시지를 earliest 로 재조회해
        // 거짓 중복으로 보이므로 사용하지 않는다).
    }

    // ── 시나리오 #7 (Task 4 — DLQ-REACHABILITY: AfterRollbackProcessor 명시 연결 검증) ──────

    /**
     * 갭-수정-검증(DLQ-REACHABILITY Task 4) — 기존 갭-문서화 단정을 반전한다.
     *
     * <p>{@code KafkaConsumerConfig} 가 {@code factory.setAfterRollbackProcessor(...)} 로
     * {@code DefaultAfterRollbackProcessor} 를 명시 연결하면서, EOS {@code commitTransaction()}
     * 반복 실패도 {@code kafkaErrorHandler} 와 동일한 {@link org.springframework.kafka.listener.DeadLetterPublishingRecoverer}
     * 빈을 재사용해 DLQ 로 발행하게 됐다(독립 backoff
     * {@code payment.kafka.after-rollback.backoff.*}, 채택 기본값 1000ms×5, 테스트는 interval 만 200ms 로 단축).
     *
     * <p>1차 배달(markPaymentAsDone)은 JPA inner 커밋이 EOS 커밋 실패와 무관하게 RDB 에 반영되고,
     * 이후 재배달은 모두 종결 가드(DONE+APPROVED 재배달 신호)로 흡수돼 재고 확정을 재발행한다.
     * 재발행도 같은 EOS 프로듀서 트랜잭션 안에서 수행되므로 commitTransaction() 이 매번 실패하면
     * 발행 자체가 매번 abort 된다(read_committed 컨슈머에 노출되지 않음) — 따라서 stock-committed
     * 는 여전히 0건(완전 유실, 잔여 over-sell 위험은 설계 §미해결 위험 그대로 유지)이지만,
     * 메시지 자체는 더 이상 조용히 스킵되지 않고 {@code events.confirmed.dlq} 로 가시화된다.
     *
     * <p>off-by-one 실측: {@code FixedBackOff(interval, maxAttempts=5)} 는 1차 시도 포함
     * 총 6회 시도(1차 + 5회 재시도)까지 허용하고 6번째 실패에서 recoverer 를 발동한다
     * (#2 시나리오의 {@code times(6)} 단정과 동일 규칙). 1차 배달은 markPaymentAsDone 경로이고
     * 나머지 5회 재배달은 종결 가드 재발행 경로이므로, terminalResendMetrics(DONE) 증가량은
     * maxAttempts 와 동일한 5다.
     */
    @Test
    @DisplayName("#7 결정적 EOS 커밋 실패 주입(갭 수정 검증): 채택 max-attempts(5) 소진 → AfterRollbackProcessor"
            + " 명시 연결로 confirmed.dlq 1건 도달 + payment DONE 유지 + dedupe row 1건 유지"
            + " + stock-committed 0건(잔여 위험) + PaymentEosCommitFailureMetrics 증가")
    void shouldReachDlqAfterExhaustingAfterRollbackBackoff() throws Exception {
        // given — READY(IN_PROGRESS) 결제 + 채택 max-attempts(5) 소진 + 1회 결정적 실패 주입
        // (1차 배달 + 5회 재시도 = 6회 commitTransaction() 호출 모두 실패시켜 recoverer 발동까지 보장).
        String orderId = "order-eos7-" + UUID.randomUUID();
        String eventUuid = UUID.randomUUID().toString();
        savePaymentInProgress(orderId, PRODUCT_ID, UNIT_AMOUNT);
        seedCommitTransactionFailure(6);

        ConfirmedEventMessage message = approvedMessage(orderId, UNIT_AMOUNT.longValue(), eventUuid);
        String payload = objectMapper.writeValueAsString(message);

        double terminalResendBefore = getCounterValue("payment_confirm_terminal_resend_total", "DONE");
        double eosCommitFailureBefore = getCounterValue("payment_eos_commit_failure_dlq_total");

        // when
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — confirmed.dlq 1건 도달(기존 "DLQ 미진입" 단정의 반전).
        List<String> dlqPayloads = pollConfirmedDlq(orderId, Duration.ofSeconds(30));
        assertThat(dlqPayloads).hasSize(1);

        // payment 는 DONE 으로 전이됐다(1차 배달의 JPA inner 커밋은 EOS 커밋 실패와 무관하게 반영).
        PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.DONE);

        // dedupe row 1건 유지 — 회복 후 재주입 복구 전제 보존. 1차 배달에서만 동반 커밋,
        // 이후 재시도는 모두 종결 가드로 흡수.
        assertThat(countDedupeRow(eventUuid)).isEqualTo(1);

        // markPaymentAsDone 은 1차 배달에서만 호출 — 이후 재시도(종결 가드 재발행)에서는 재호출되지 않는다.
        verify(paymentCommandUseCase, times(1))
                .markPaymentAsDone(any(PaymentEvent.class), any(Instant.class));

        // terminalResendMetrics(DONE) 증가량 = 채택 max-attempts(5) — off-by-one 실측 확정치.
        assertThat(getCounterValue("payment_confirm_terminal_resend_total", "DONE"))
                .isEqualTo(terminalResendBefore + 5.0);

        // PaymentEosCommitFailureMetrics 증가 — AfterRollbackProcessor recoverer 발화(=DLQ 도달) 1회.
        assertThat(getCounterValue("payment_eos_commit_failure_dlq_total"))
                .isEqualTo(eosCommitFailureBefore + 1.0);

        // stock-committed 0건(잔여 위험 그대로) — 종결 가드의 재발행 시도 전부가 같은 EOS 프로듀서
        // 트랜잭션 안에서 매번 commitTransaction() 실패로 abort 돼, read_committed 컨슈머에는
        // 단 1건도 가시화되지 않는다(over-sell 잔여 위험, 설계 §미해결 위험 — 자동 복구는 범위 밖).
        List<StockCommittedEvent> stockEvents = pollStockCommitted(orderId, 0, Duration.ofSeconds(3));
        assertThat(stockEvents).isEmpty();
    }

    // ── 시나리오 #8 (Task 8 — 승인 반영 트랜잭션에서 확정 표시) ──────────────────────────

    @Test
    @DisplayName("#8 승인 반영 시 선차감 기록 확정: 그 주문의 모든 상품 잡음 기록이 확정 상태로 저장된다 (다중 상품)")
    void shouldCommitAllStockHoldRecordsOnApproval() throws Exception {
        // given — multi-product 결제 + 상품별 잡음 선차감 기록을 미리 심는다
        String orderId = "order-eos8-" + UUID.randomUUID();
        String eventUuid = UUID.randomUUID().toString();
        BigDecimal totalAmount = UNIT_AMOUNT.multiply(BigDecimal.valueOf(2));
        savePaymentInProgressMultiProduct(orderId, totalAmount);
        seedNoiseHold(orderId, PRODUCT_ID, ORDER_QUANTITY);
        seedNoiseHold(orderId, PRODUCT_ID_2, ORDER_QUANTITY);

        ConfirmedEventMessage message = approvedMessage(orderId, totalAmount.longValue(), eventUuid);
        String payload = objectMapper.writeValueAsString(message);

        // when
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — payment DONE 전이
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId)
                            .orElseThrow();
                    assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.DONE);
                });

        // 두 상품 기록 모두 확정으로 저장된다
        assertThat(holdStatus(orderId, PRODUCT_ID)).isEqualTo(StockHoldRecordStatus.COMMITTED);
        assertThat(holdStatus(orderId, PRODUCT_ID_2)).isEqualTo(StockHoldRecordStatus.COMMITTED);
    }

    // ── 시나리오 #9 (Task 8 — 확정 표시 롤백 동조) ────────────────────────────────────

    @Test
    @DisplayName("#9 확정 표시 롤백 동조: 확정 표시 이후 같은 트랜잭션에서 실패하면 결제 전이와 확정 표시가 함께 롤백된다")
    void shouldRollbackStockHoldCommitTogetherWithPaymentTransition() throws Exception {
        // given — stockCommittedKafkaTemplate.send (확정 표시 다음 단계) 에서 결정적 실패를 주입한다.
        // handleApproved 순서가 markPaymentAsDone → commitAllByOrderId → sendStockCommittedEvents 이므로
        // 이 시점의 실패는 이미 반영된 확정 표시(JPA 갱신)를 가진 채로 handle() 트랜잭션을 롤백시킨다.
        String orderId = "order-eos9-" + UUID.randomUUID();
        String eventUuid = UUID.randomUUID().toString();
        savePaymentInProgress(orderId, PRODUCT_ID, UNIT_AMOUNT);
        seedNoiseHold(orderId, PRODUCT_ID, ORDER_QUANTITY);

        doThrow(new RuntimeException("stock-committed 발행 실패 시뮬레이션 — 확정 표시 롤백 동조 검증"))
                .when(stockCommittedKafkaTemplate).send(anyString(), anyString(), anyString());

        ConfirmedEventMessage message = approvedMessage(orderId, UNIT_AMOUNT.longValue(), eventUuid);
        String payload = objectMapper.writeValueAsString(message);

        // when
        confirmedDlqKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);

        // then — 5회 retry 후 DLQ (interval 200ms × 5 = 최소 1s, 여유 30s)
        List<String> dlqPayloads = pollConfirmedDlq(orderId, Duration.ofSeconds(30));
        assertThat(dlqPayloads).hasSize(1);

        // 결제 전이가 롤백돼 IN_PROGRESS 로 남는다
        PaymentEventEntity entity = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(PaymentEventStatus.IN_PROGRESS);

        // 같은 트랜잭션이라 확정 표시도 함께 롤백돼 잡음 상태로 남는다
        assertThat(holdStatus(orderId, PRODUCT_ID)).isEqualTo(StockHoldRecordStatus.NOISE);

        // dedupe 0 row — RDB 롤백 확인
        assertThat(countDedupeRow(eventUuid)).isZero();
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────────

    /**
     * IN_PROGRESS 상태 PaymentEvent + PaymentOrder 1건 저장.
     */
    private void savePaymentInProgress(String orderId, Long productId, BigDecimal amount) {
        PaymentEventEntity event = buildPaymentEventEntity(orderId, PaymentEventStatus.IN_PROGRESS, amount);
        PaymentEventEntity savedEvent = jpaPaymentEventRepository.save(event);

        PaymentOrderEntity order = PaymentOrderEntity.builder()
                .paymentEventId(savedEvent.getId())
                .orderId(orderId)
                .productId(productId)
                .quantity(ORDER_QUANTITY)
                .totalAmount(amount)
                .status(PaymentOrderStatus.EXECUTING)
                .build();
        jpaPaymentOrderRepository.save(order);
    }

    /**
     * IN_PROGRESS 상태 PaymentEvent + PaymentOrder 2건 (multi-product) 저장.
     */
    private void savePaymentInProgressMultiProduct(String orderId, BigDecimal totalAmount) {
        PaymentEventEntity event = buildPaymentEventEntity(orderId, PaymentEventStatus.IN_PROGRESS, totalAmount);
        PaymentEventEntity savedEvent = jpaPaymentEventRepository.save(event);

        PaymentOrderEntity order1 = PaymentOrderEntity.builder()
                .paymentEventId(savedEvent.getId())
                .orderId(orderId)
                .productId(PRODUCT_ID)
                .quantity(ORDER_QUANTITY)
                .totalAmount(UNIT_AMOUNT)
                .status(PaymentOrderStatus.EXECUTING)
                .build();
        PaymentOrderEntity order2 = PaymentOrderEntity.builder()
                .paymentEventId(savedEvent.getId())
                .orderId(orderId)
                .productId(PRODUCT_ID_2)
                .quantity(ORDER_QUANTITY)
                .totalAmount(UNIT_AMOUNT)
                .status(PaymentOrderStatus.EXECUTING)
                .build();
        jpaPaymentOrderRepository.save(order1);
        jpaPaymentOrderRepository.save(order2);
    }

    /**
     * QUARANTINED 상태 PaymentEvent + PaymentOrder 1건 저장.
     */
    private void savePaymentQuarantined(String orderId, Long productId, BigDecimal amount) {
        PaymentEventEntity event = buildPaymentEventEntity(orderId, PaymentEventStatus.QUARANTINED, amount);
        PaymentEventEntity savedEvent = jpaPaymentEventRepository.save(event);

        PaymentOrderEntity order = PaymentOrderEntity.builder()
                .paymentEventId(savedEvent.getId())
                .orderId(orderId)
                .productId(productId)
                .quantity(ORDER_QUANTITY)
                .totalAmount(amount)
                .status(PaymentOrderStatus.EXECUTING)
                .build();
        jpaPaymentOrderRepository.save(order);
    }

    /**
     * DONE(종결) 상태 PaymentEvent + PaymentOrder 1건 저장.
     * 종결 가드 재발행(#3) 시나리오용 — 첫 처리가 이미 완료된 상태를 모사한다.
     */
    private void savePaymentDone(String orderId, Long productId, BigDecimal amount) {
        PaymentEventEntity event = buildPaymentEventEntity(orderId, PaymentEventStatus.DONE, amount);
        PaymentEventEntity savedEvent = jpaPaymentEventRepository.save(event);

        PaymentOrderEntity order = PaymentOrderEntity.builder()
                .paymentEventId(savedEvent.getId())
                .orderId(orderId)
                .productId(productId)
                .quantity(ORDER_QUANTITY)
                .totalAmount(amount)
                .status(PaymentOrderStatus.EXECUTING)
                .build();
        jpaPaymentOrderRepository.save(order);
    }

    /**
     * 상품 하나에 대한 선차감 기록을 잡음 상태로 심는다. #8·#9 전용 — 확정 진입(Task 6/7)을
     * 거치지 않고 확정 표시(Task 8) 배선만 독립적으로 검증하기 위한 직접 시드.
     */
    private void seedNoiseHold(String orderId, Long productId, int quantity) {
        PaymentOrder order = PaymentOrder.allArgsBuilder()
                .orderId(orderId)
                .productId(productId)
                .quantity(quantity)
                .totalAmount(UNIT_AMOUNT)
                .allArgsBuild();
        stockHoldRecordRepository.openHold(orderId, order);
    }

    /**
     * 주문번호·상품번호 조합의 선차감 기록 상태를 조회한다.
     */
    private StockHoldRecordStatus holdStatus(String orderId, Long productId) {
        PaymentOrder order = PaymentOrder.allArgsBuilder()
                .orderId(orderId)
                .productId(productId)
                .allArgsBuild();
        return stockHoldRecordRepository.findSnapshot(orderId, order)
                .map(StockHoldRecordSnapshot::status)
                .orElseThrow(() -> new IllegalStateException(
                        "선차감 기록이 없다 — orderId=" + orderId + ", productId=" + productId));
    }

    /**
     * 지정 메트릭명·status 라벨의 현재 카운터 값을 조회한다. 카운터가 아직 등록되지 않았으면 0을 반환한다.
     */
    private double getCounterValue(String metricName, String statusLabel) {
        Counter counter = meterRegistry.find(metricName)
                .tag("status", statusLabel)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    /**
     * 라벨 없는 단일 카운터({@code PaymentEosCommitFailureMetrics} 등)의 현재 값을 조회한다.
     * 카운터가 아직 등록되지 않았으면 0을 반환한다.
     */
    private double getCounterValue(String metricName) {
        Counter counter = meterRegistry.find(metricName).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private PaymentEventEntity buildPaymentEventEntity(
            String orderId, PaymentEventStatus status, BigDecimal totalAmount) {
        return PaymentEventEntity.builder()
                .buyerId(1L)
                .sellerId(2L)
                .orderName("EOS 테스트 상품 — " + orderId)
                .orderId(orderId)
                .paymentKey("pay-key-" + orderId)
                .gatewayType(PaymentGatewayType.TOSS)
                .status(status)
                .lastStatusChangedAt(Instant.now())
                .build();
    }

    /**
     * APPROVED ConfirmedEventMessage 생성.
     * approvedAt 은 현재 시각 ISO-8601 OffsetDateTime 문자열.
     */
    private static ConfirmedEventMessage approvedMessage(String orderId, Long amount, String eventUuid) {
        String approvedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        return new ConfirmedEventMessage(orderId, "APPROVED", null, amount, approvedAt, eventUuid);
    }

    /**
     * payment_event_dedupe 테이블에 dedupe row 를 직접 삽입한다.
     * 중복 메시지 시뮬레이션용 — 실서비스에서 동일 메시지가 두 번 배달될 때 첫 번째 처리 후 dedupe row 가 존재하는 상황.
     */
    private void insertDedupeRow(String eventUuid, String orderId, String status) {
        Long paymentEventId = jpaPaymentEventRepository.findByOrderId(orderId)
                .orElseThrow()
                .getId();
        namedParameterJdbcTemplate.update(
                "INSERT IGNORE INTO payment_event_dedupe "
                        + "(event_uuid, order_id, status, received_at, expires_at) "
                        + "VALUES (:eventUuid, :orderId, :status, NOW(), DATE_ADD(NOW(), INTERVAL 8 DAY))",
                Map.of(
                        "eventUuid", eventUuid,
                        "orderId", paymentEventId,
                        "status", status
                )
        );
    }

    /**
     * payment_event_dedupe 테이블에서 eventUuid 에 해당하는 row 수 조회.
     */
    private int countDedupeRow(String eventUuid) {
        Integer count = namedParameterJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_event_dedupe WHERE event_uuid = :eventUuid",
                Map.of("eventUuid", eventUuid),
                Integer.class
        );
        return count != null ? count : 0;
    }

    /**
     * payment.events.stock-committed 토픽을 read_committed isolation 으로 폴링해
     * 지정 orderId 에 해당하는 StockCommittedEvent 목록을 반환한다.
     *
     * <p>설계 이유: EmbeddedKafka 는 테스트 간 토픽을 공유하므로 이전 테스트의 메시지가
     * 후속 테스트 consumer 에서 earliest 로 소비될 수 있다.
     * orderId 필터로 현재 테스트의 메시지만 선별한다.
     *
     * @param filterOrderId 필터링할 orderId (각 테스트마다 unique)
     * @param expectedCount 기대하는 메시지 수 (0이면 즉시 폴링 후 반환)
     * @param timeout       폴링 대기 최대 시간
     */
    private List<StockCommittedEvent> pollStockCommitted(
            String filterOrderId, int expectedCount, Duration timeout) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-stock-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        List<StockCommittedEvent> result = new ArrayList<>();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(PaymentTopics.EVENTS_STOCK_COMMITTED));
            long deadline = System.currentTimeMillis() + timeout.toMillis();

            while (System.currentTimeMillis() < deadline && result.size() < expectedCount) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                records.forEach(record -> {
                    StockCommittedEvent event = deserializeStockCommittedEvent(record.value());
                    if (filterOrderId.equals(event.orderId())) {
                        result.add(event);
                    }
                });
            }

            // expectedCount == 0 인 경우 충분히 폴링해 해당 orderId 메시지 없음 확인
            if (expectedCount == 0) {
                long extraDeadline = System.currentTimeMillis() + Duration.ofMillis(500).toMillis();
                while (System.currentTimeMillis() < extraDeadline) {
                    ConsumerRecords<String, String> extra = consumer.poll(Duration.ofMillis(200));
                    extra.forEach(record -> {
                        StockCommittedEvent event = deserializeStockCommittedEvent(record.value());
                        if (filterOrderId.equals(event.orderId())) {
                            result.add(event);
                        }
                    });
                }
            }
        }

        return result;
    }

    private StockCommittedEvent deserializeStockCommittedEvent(String json) {
        try {
            return objectMapper.readValue(json, StockCommittedEvent.class);
        } catch (Exception e) {
            throw new IllegalStateException("StockCommittedEvent 역직렬화 실패: " + json, e);
        }
    }

    /**
     * payment.events.confirmed.dlq 토픽을 폴링해 지정 orderId 에 해당하는 원본 페이로드 목록을 반환한다.
     *
     * <p>#7(Task 3 DLQ bound) 전용 — DLQ 라우팅을 호출 횟수 검증(verify times)이 아닌 실제 토픽
     * 가시화로 직접 단정한다(원본 페이로드가 손상 없이 DLQ 로 전달됐는지까지 함께 확인).
     *
     * @param filterOrderId 필터링할 orderId (각 테스트마다 unique)
     * @param timeout       폴링 대기 최대 시간
     */
    private List<String> pollConfirmedDlq(String filterOrderId, Duration timeout) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlq-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        List<String> result = new ArrayList<>();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(PaymentTopics.EVENTS_CONFIRMED_DLQ));
            long deadline = System.currentTimeMillis() + timeout.toMillis();

            while (System.currentTimeMillis() < deadline && result.isEmpty()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                records.forEach(record -> {
                    if (filterOrderId.equals(record.key())) {
                        result.add(record.value());
                    }
                });
            }
        }

        return result;
    }
}
