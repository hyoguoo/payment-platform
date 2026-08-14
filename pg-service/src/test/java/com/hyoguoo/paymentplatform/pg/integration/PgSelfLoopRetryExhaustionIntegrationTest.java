package com.hyoguoo.paymentplatform.pg.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgEventPublisherPort;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.service.PgDlqService;
import com.hyoguoo.paymentplatform.pg.core.common.metrics.PgDlqReachMetrics;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.fake.FakePgGatewayStrategy;
import com.hyoguoo.paymentplatform.pg.infrastructure.repository.JpaPgInboxRepository;
import com.hyoguoo.paymentplatform.pg.presentation.port.PgConfirmCommandService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Track P self-loop 한도 소진 → QUARANTINED 종단 통합 테스트.
 *
 * <p>벤더가 지속 Retryable(일시 오류) 응답을 반환하는 상황에서, pg-service 내부 self-loop
 * (워커 → pg_inbox.attempt 영속 증가 → 재시도 outbox → relay → {@code payment.commands.confirm}
 * 재진입 → 워커 재처리)이 **무한 반복하지 않고** {@code RetryPolicy}(도메인 클래스,
 * com.hyoguoo.paymentplatform.pg.domain.RetryPolicy)의 MAX_ATTEMPTS(4) 소진 시
 * DLQ(payment.commands.confirm.dlq) → {@link PgDlqService} → QUARANTINED 전이로 종결되는지 검증한다.
 *
 * <p>Kafka 네트워크 경로 대체: {@code spring.kafka.bootstrap-servers} 미설정 +
 * consumer auto-startup=false 로 실제 Kafka 브로커 없이, {@link PgEventPublisherPort} 를
 * {@link SelfLoopRelayPgEventPublisher} 로 교체해 {@code payment.commands.confirm} /
 * {@code payment.commands.confirm.dlq} 토픽 발행을 각각 {@link PgConfirmCommandService#handle}
 * / {@link PgDlqService#handle} 직접 호출로 라우팅한다 — {@code PgOutboxRelayService} →
 * {@code PaymentConfirmConsumer} / {@code PaymentConfirmDlqConsumer} 경로를 인메모리로 대체할 뿐,
 * pg_inbox/pg_outbox 워커·TX 경계·{@code pg_inbox.attempt} 영속 증가 로직은 실제 프로덕션 코드 그대로
 * 동작한다({@link PgConfirmListenerSplitIntegrationTest} 와 동일하게 listener/consumer 만 대체).
 *
 * <p>벤더 Retryable 주입: {@code FakePgGatewayStrategy} 는 happy-path 전용(FAIL/QUARANTINE 미지원)이므로
 * {@code @MockitoSpyBean} 으로 {@code confirm()} 을 {@code doThrow(PgGatewayRetryableException)} 오버라이드한다.
 *
 * <p>백오프 대기: {@code RetryPolicy}(base=2s, multiplier=3, MAX_ATTEMPTS=4)는 하드코딩 상수라
 * 운영 코드 변경 없이 단축할 수 없다. {@code computeBackoff} 는 실패한 attempt 값 그대로 계산되므로
 * attempt=1 실패 후 재시도 backoff(jitter 포함, computeBackoff(1))는 [1.5s,2.5s],
 * attempt=2 실패 후는 [4.5s,7.5s], attempt=3 실패 후는 [13.5s,22.5s] — 누적 평균 26s, 최악 32.5s 소요된다
 * (attempt=4는 shouldRetry가 false라 DLQ로 즉시 빠지며 추가 backoff 없음). {@code pg.scheduler.polling-worker.fixed-delay-ms}
 * 는 기본값(2000ms)을 유지해 availableAt 경과 후 폴링 워커가 안전망으로 relay 한다.
 * Awaitility 타임아웃은 60s로 최악 케이스(32.5s)를 여유 있게 커버하며 무한 대기는 아니다.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@Tag("integration")
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@DisplayName("Track P self-loop 한도 소진 → QUARANTINED 종단 통합 테스트")
class PgSelfLoopRetryExhaustionIntegrationTest {

    // ─── Testcontainers MySQL ─────────────────────────────────────────────────

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("pg-test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // FakePgGatewayStrategy 활성화 (TossPaymentGatewayStrategy / NicepayPaymentGatewayStrategy 비활성)
        registry.add("pg.gateway.type", () -> "fake");
        // 폴링 워커는 기본값 유지 — availableAt 경과 후 안전망 relay 역할(self-loop 전제).
        // spring.kafka.bootstrap-servers — 존재하지 않는 서버로 설정.
        // KafkaTemplate lazy init 특성상 실제 send() 미호출 시 연결 시도 없음(실제 publish 는
        // SelfLoopRelayPgEventPublisher 가 가로채 KafkaTemplate 자체를 호출하지 않는다).
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9099");
        // Kafka consumer auto-startup 비활성 — 컨텍스트 시작 시 Kafka 연결 시도 방지
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    // ─── TestConfiguration — self-loop relay 대체 ────────────────────────────

    /**
     * {@link PgEventPublisherPort} 를 대체해 Kafka 네트워크 없이 self-loop 를 인메모리로 재현한다.
     *
     * <ul>
     *   <li>{@code payment.commands.confirm} 발행 → {@link PgConfirmCommandService#handle} 직접 재호출
     *       ({@code PaymentConfirmConsumer} 대체 — attempt 헤더는 결정 노트대로 미사용이라 1 고정).</li>
     *   <li>{@code payment.commands.confirm.dlq} 발행 → {@link PgDlqService#handle} 직접 호출
     *       ({@code PaymentConfirmDlqConsumer} 대체).</li>
     *   <li>{@code payment.events.confirmed} 발행 → payment-service 부재이므로 카운트만 기록.</li>
     * </ul>
     */
    static class SelfLoopRelayPgEventPublisher implements PgEventPublisherPort {

        private final PgConfirmCommandService pgConfirmCommandService;
        private final PgDlqService pgDlqService;
        private final ObjectMapper objectMapper;
        private final AtomicInteger confirmRelayCount = new AtomicInteger();
        private final AtomicInteger dlqRelayCount = new AtomicInteger();
        private final AtomicInteger eventsConfirmedCount = new AtomicInteger();

        SelfLoopRelayPgEventPublisher(
                PgConfirmCommandService pgConfirmCommandService,
                PgDlqService pgDlqService,
                ObjectMapper objectMapper
        ) {
            this.pgConfirmCommandService = pgConfirmCommandService;
            this.pgDlqService = pgDlqService;
            this.objectMapper = objectMapper;
        }

        @Override
        public void publish(String topic, String key, Object payload, Map<String, byte[]> headers) {
            try {
                routeByTopic(topic, payload);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(
                        "SelfLoopRelayPgEventPublisher: topic=" + topic + " 라우팅 실패", e);
            }
        }

        private void routeByTopic(String topic, Object payload) throws JsonProcessingException {
            if (PgTopics.COMMANDS_CONFIRM.equals(topic)) {
                PgConfirmCommand command = objectMapper.readValue((String) payload, PgConfirmCommand.class);
                confirmRelayCount.incrementAndGet();
                // attempt 헤더는 결정 노트대로 미사용 — 실제 attempt SoT 는 pg_inbox.attempt(DB) 다.
                pgConfirmCommandService.handle(command, 1, null);
            } else if (PgTopics.COMMANDS_CONFIRM_DLQ.equals(topic)) {
                PgConfirmCommand command = objectMapper.readValue((String) payload, PgConfirmCommand.class);
                dlqRelayCount.incrementAndGet();
                pgDlqService.handle(command);
            } else if (PgTopics.EVENTS_CONFIRMED.equals(topic)) {
                eventsConfirmedCount.incrementAndGet();
            }
        }

        int getConfirmRelayCount() {
            return confirmRelayCount.get();
        }

        int getDlqRelayCount() {
            return dlqRelayCount.get();
        }

        int getEventsConfirmedCount() {
            return eventsConfirmedCount.get();
        }
    }

    @TestConfiguration
    static class SelfLoopRelayConfig {

        @Bean
        @Primary
        public PgEventPublisherPort selfLoopRelayPgEventPublisher(
                PgConfirmCommandService pgConfirmCommandService,
                PgDlqService pgDlqService,
                ObjectMapper objectMapper
        ) {
            return new SelfLoopRelayPgEventPublisher(pgConfirmCommandService, pgDlqService, objectMapper);
        }
    }

    // ─── SpyBean / 의존성 ─────────────────────────────────────────────────────

    @MockitoSpyBean
    private FakePgGatewayStrategy fakePgGatewayStrategy;

    @Autowired
    private PgConfirmCommandService pgConfirmCommandService;

    @Autowired
    private PgInboxRepository pgInboxRepository;

    @Autowired
    private JpaPgInboxRepository jpaPgInboxRepository;

    @Autowired
    private PgEventPublisherPort pgEventPublisherPort;

    @Autowired
    private PgDlqReachMetrics pgDlqReachMetrics;

    @Autowired
    private MeterRegistry meterRegistry;

    // ─── 테스트 데이터 상수 ──────────────────────────────────────────────────

    private static final long AMOUNT = 10_000L;

    @BeforeEach
    void setUp() {
        jpaPgInboxRepository.deleteAll();
    }

    @Test
    @DisplayName("벤더 지속 Retryable 응답 → self-loop 재시도 누적 → 한도(4) 소진 → DLQ → QUARANTINED 종결, 무한 반복 아님")
    void selfLoopRetryExhaustion_terminatesAsQuarantined() {
        // given — 벤더 confirm 호출이 항상 Retryable(일시 오류) 예외를 던지도록 고정.
        Mockito.doThrow(PgGatewayRetryableException.of("FAKE_RETRYABLE_5XX"))
                .when(fakePgGatewayStrategy).confirm(any());

        String orderId = "order-exhaustion-" + UUID.randomUUID();
        PgConfirmCommand command = new PgConfirmCommand(
                orderId, "pay-key-" + orderId, BigDecimal.valueOf(AMOUNT),
                PgVendorType.TOSS, UUID.randomUUID().toString());

        double quarantineCounterBefore = readQuarantineCounter();

        // when — listener 진입(최초 1회) — 이후는 워커 + self-loop relay 가 자동으로 이어간다.
        pgConfirmCommandService.handle(command, 1, null);

        // then — 한도 소진 후 QUARANTINED 종결까지 대기(attempt 1,2,3 실패 후 backoff 합산 평균 26s,
        // 최악 32.5s + 폴링 워커 fixed-delay(2s) 안전망 지연 누적분 여유 → 60s 타임아웃).
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    Optional<PgInbox> inbox = pgInboxRepository.findByOrderId(orderId);
                    assertThat(inbox).isPresent();
                    assertThat(inbox.get().getStatus())
                            .as("한도 소진 후 최종 상태는 QUARANTINED 여야 한다")
                            .isEqualTo(PgInboxStatus.QUARANTINED);
                });

        PgInbox finalInbox = pgInboxRepository.findByOrderId(orderId).orElseThrow();
        // 격리 직행 대신 관문이 벤더에 최종 상태를 조회한다. 이 시나리오는 confirm() 을 항상
        // Retryable 실패로 고정해 모의 벤더에 처리 기록이 남지 않으므로, 관문 조회가 실행 시
        // 예외로 떨어져 사유가 조회 실패(FCG_INDETERMINATE)로 확정된다.
        assertThat(finalInbox.getReasonCode())
                .as("격리 사유는 관문 조회 실패(FCG_INDETERMINATE)여야 한다")
                .isEqualTo("FCG_INDETERMINATE");

        // then — 무한 반복이 아니라 한도 도달로 종결됐는지: QUARANTINED 전이 시점에 이미 in-flight
        // 였던 마지막 재시도 outbox 1개가 뒤늦게 relay 되며 terminal reemit(PgTerminalReemitService,
        // 무해한 상태 재통보)을 1회 더 트리거할 수 있다 — 이는 좀비 재호출 window 멱등성 영역으로
        // 이 태스크 범위 밖(결정 노트 참고)이다. 검증 대상은 "값이 계속 증가하지 않고 안정화되는지"
        // 이며, 단발성 reemit 가산까지 흡수하도록 두 차례 polling 주기 간 값이 동일한지로 확인한다.
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    int first = ((SelfLoopRelayPgEventPublisher) pgEventPublisherPort).getConfirmRelayCount();
                    awaitShortQuietPeriod();
                    int second = ((SelfLoopRelayPgEventPublisher) pgEventPublisherPort).getConfirmRelayCount();
                    assertThat(second)
                            .as("QUARANTINED 종결 후 COMMANDS_CONFIRM self-loop 재진입 횟수가 더 이상 "
                                    + "증가하지 않아야 한다(무한 반복 아님)")
                            .isEqualTo(first);
                });

        // then — DLQ 경로(payment.commands.confirm.dlq → PgDlqService.handle)가 정확히 1회 도달했다.
        assertThat(((SelfLoopRelayPgEventPublisher) pgEventPublisherPort).getDlqRelayCount())
                .as("DLQ 라우팅은 한도 소진 시 1회 도달해야 한다(중복 진입 없음)")
                .isEqualTo(1);

        // then — payment.events.confirmed(QUARANTINED 통보) 가 발행됐다.
        assertThat(((SelfLoopRelayPgEventPublisher) pgEventPublisherPort).getEventsConfirmedCount())
                .as("QUARANTINED 통보(events.confirmed) 가 발행돼야 한다")
                .isGreaterThanOrEqualTo(1);

        // then — PgDlqReachMetrics 카운터가 1 증가했다(QUARANTINED 전이 성공 지점, 멱등).
        double quarantineCounterAfter = readQuarantineCounter();
        assertThat(quarantineCounterAfter - quarantineCounterBefore)
                .as("격리 도달 metric 이 정확히 1 증가해야 한다")
                .isEqualTo(1.0);

        // then — attempt 가 정확히 4임은 강제하지 않는다(동시 진입 over-count 수용 — 결정 노트).
        // 다만 한도(MAX_ATTEMPTS=4) 미만으로 조기 종결되지 않았는지만 최소 검증한다.
        assertThat(finalInbox.getAttempt())
                .as("attempt 는 한도(4) 도달 전에 조기 종결되지 않아야 한다(최소 4 이상)")
                .isGreaterThanOrEqualTo(4);
    }

    private double readQuarantineCounter() {
        Counter counter = meterRegistry.find("pg_retry_exhausted_quarantine_total").counter();
        return counter != null ? counter.count() : 0.0;
    }

    /**
     * 두 polling 시점 사이 안정화 여부를 비교하기 위한 짧은 정적 대기(polling 워커 주기 1~2회분).
     */
    private void awaitShortQuietPeriod() {
        try {
            Thread.sleep(3_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("정적 대기 중 인터럽트 발생", e);
        }
    }
}
