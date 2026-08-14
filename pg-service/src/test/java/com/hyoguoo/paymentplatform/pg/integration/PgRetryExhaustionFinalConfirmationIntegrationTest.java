package com.hyoguoo.paymentplatform.pg.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.application.port.in.PgInboxProcessUseCase;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.service.PgDlqService;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.fake.FakePgGatewayStrategy;
import com.hyoguoo.paymentplatform.pg.infrastructure.repository.JpaPgInboxRepository;
import com.hyoguoo.paymentplatform.pg.infrastructure.repository.JpaPgOutboxRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 대기열 소비부터 관문({@link com.hyoguoo.paymentplatform.pg.application.service.PgFinalConfirmationGate})을
 * 거쳐 종결까지의 경로를 실제 DB 위에서 검증한다.
 *
 * <p>검증 범위:
 * <ul>
 *   <li>비잠금 상태로 대기열에 도착한 건이 관문의 벤더 조회 결과에 따라 승인/실패/격리(조회 실패·
 *       부분 취소) 네 갈래로 갈리고, 각각 발행이 정확히 1건이다.</li>
 *   <li>승인 시나리오는 {@link FakePgGatewayStrategy} 의 {@code fake-lost-} 접두어(확정은 매번 재시도
 *       가능 실패, 조회는 승인)로 "소진 후 벤더가 승인으로 답하는" 장면을 그대로 재현한다.</li>
 *   <li>대기열 처리 도중(벤더 조회 응답 대기 구간) 다른 경로가 같은 기록을 먼저 종결시키면, 관문의
 *       반영 0건 가드가 대기열 처리 쪽의 중복 발행을 막는다 — 신호(latch) 로 그 구간을 결정적으로
 *       연다.</li>
 * </ul>
 *
 * <p>인프라는 {@code PgDuplicateApprovalSettlementIntegrationTest} 와 동일 패턴을 따른다 — Testcontainers
 * MySQL + {@code pg.gateway.type=fake} + 스케줄러/Kafka 비활성 + {@code FakePgGatewayStrategy}
 * {@code @MockitoSpyBean} 오버라이드. 대기열 소비는 Kafka DLQ 컨슈머를 거치지 않고
 * {@link PgDlqService#handle} 을 직접 호출해 재현한다 — 검증 대상은 그 이후(관문 호출부터 종결까지)다.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@Tag("integration")
@Testcontainers
@ActiveProfiles("test")
@DisplayName("대기열 소비 → 관문 → 종결 통합 검증")
class PgRetryExhaustionFinalConfirmationIntegrationTest {

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
        // FakePgGatewayStrategy 활성화 (Toss/NicePay 비활성)
        registry.add("pg.gateway.type", () -> "fake");
        // 스케줄러 비활성 — 좀비 폴링/발행 폴링이 테스트 흐름을 방해하지 않도록 한다
        registry.add("pg.scheduler.inbox-polling-worker.fixed-delay-ms", () -> "3600000");
        registry.add("pg.scheduler.polling-worker.fixed-delay-ms", () -> "3600000");
        // Kafka — 존재하지 않는 서버로 설정. lazy init 특성상 실제 send() 미호출 시 연결 시도 없음
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9099");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    // ─── SpyBean / 의존성 ─────────────────────────────────────────────────────

    @MockitoSpyBean
    private FakePgGatewayStrategy fakePgGatewayStrategy;

    @Autowired
    private PgDlqService pgDlqService;

    @Autowired
    private PgInboxRepository pgInboxRepository;

    @Autowired
    private PgInboxProcessUseCase pgInboxProcessUseCase;

    @Autowired
    private JpaPgInboxRepository jpaPgInboxRepository;

    @Autowired
    private JpaPgOutboxRepository jpaPgOutboxRepository;

    // ─── 테스트 데이터 상수 ──────────────────────────────────────────────────

    private static final long AMOUNT = 12_000L;
    private static final String APPROVED_AT_RAW = "2026-08-14T00:00:00Z";

    @BeforeEach
    void setUp() {
        jpaPgInboxRepository.deleteAll();
        jpaPgOutboxRepository.deleteAll();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 승인 — fake-lost- 시나리오(확정은 재시도 가능 실패, 조회는 승인)로 소진 후 자동 승인 재현
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("소진 건 — 벤더 조회가 승인으로 답하면 승인 종결되고 발행은 1건")
    void 소진건_벤더가_승인응답_승인종결되고_발행1건() {
        // given — fake-lost- 접두어로 확정 호출을 먼저 태워 "벤더는 승인을 끝냈지만 확정 응답은
        // 재시도 가능 실패로만 돌아온" 상태를 만든다. 재시도 소진 자체는 이 태스크 범위 밖이라
        // 대기열 도착 직전 상태(IN_PROGRESS)로 직접 둔다.
        String orderId = "order-dlq-approved-" + UUID.randomUUID();
        String paymentKey = "fake-lost-" + orderId;
        Long inboxId = pgInboxRepository.insertPending(orderId, AMOUNT, "TOSS", paymentKey, null);
        pgInboxRepository.transitPendingToInProgress(inboxId);

        assertThatThrownBy(() -> fakePgGatewayStrategy.confirm(
                new PgConfirmRequest(orderId, paymentKey, BigDecimal.valueOf(AMOUNT), PgVendorType.TOSS)))
                .as("fake-lost- 확정 호출은 매번 재시도 가능 실패여야 함")
                .isInstanceOf(PgGatewayRetryableException.class);

        // when — 대기열 소비가 관문을 부른다
        pgDlqService.handle(buildCommand(orderId, paymentKey));

        // then
        PgInbox settled = pgInboxRepository.findByOrderId(orderId).orElseThrow();
        assertThat(settled.getStatus())
                .as("벤더 조회가 승인으로 답했으므로 승인 종결이어야 함")
                .isEqualTo(PgInboxStatus.APPROVED);
        assertThat(jpaPgOutboxRepository.findByKeyAndTopicInOrderByCreatedAtAsc(
                orderId, List.of(PgTopics.EVENTS_CONFIRMED)))
                .as("발행 행이 정확히 1건이어야 함")
                .hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 실패 — 벤더 조회가 취소로 답하면 확정 실패 종결
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("소진 건 — 벤더 조회가 취소로 답하면 실패 종결되고 발행은 1건")
    void 소진건_벤더가_취소응답_실패종결되고_발행1건() {
        // given
        String orderId = "order-dlq-failed-" + UUID.randomUUID();
        String paymentKey = "pay-key-" + orderId;
        Long inboxId = pgInboxRepository.insertPending(orderId, AMOUNT, "TOSS", paymentKey, null);
        pgInboxRepository.transitPendingToInProgress(inboxId);

        doReturn(new PgStatusResult(
                paymentKey, orderId, PgPaymentStatus.CANCELED,
                BigDecimal.valueOf(AMOUNT), null, null, APPROVED_AT_RAW))
                .when(fakePgGatewayStrategy).getStatusByOrderId(orderId);

        // when
        pgDlqService.handle(buildCommand(orderId, paymentKey));

        // then
        PgInbox settled = pgInboxRepository.findByOrderId(orderId).orElseThrow();
        assertThat(settled.getStatus())
                .as("벤더 조회가 취소로 답했으므로 실패 종결이어야 함")
                .isEqualTo(PgInboxStatus.FAILED);
        assertThat(jpaPgOutboxRepository.findByKeyAndTopicInOrderByCreatedAtAsc(
                orderId, List.of(PgTopics.EVENTS_CONFIRMED)))
                .as("발행 행이 정확히 1건이어야 함")
                .hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 격리 — 조회 자체가 실행 시 예외로 실패하면 조회 실패 사유로 격리
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("소진 건 — 벤더 조회가 실패하면 조회 실패 사유로 격리되고 발행은 1건")
    void 소진건_조회실패_격리되고_사유는_조회실패() {
        // given
        String orderId = "order-dlq-indeterminate-" + UUID.randomUUID();
        String paymentKey = "pay-key-" + orderId;
        Long inboxId = pgInboxRepository.insertPending(orderId, AMOUNT, "TOSS", paymentKey, null);
        pgInboxRepository.transitPendingToInProgress(inboxId);

        doThrow(PgGatewayRetryableException.of("vendor lookup failed - integration test"))
                .when(fakePgGatewayStrategy).getStatusByOrderId(orderId);

        // when
        pgDlqService.handle(buildCommand(orderId, paymentKey));

        // then
        PgInbox settled = pgInboxRepository.findByOrderId(orderId).orElseThrow();
        assertThat(settled.getStatus())
                .as("조회 자체가 실패했으므로 격리 종결이어야 함")
                .isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(settled.getReasonCode())
                .as("격리 사유는 조회 실패(FCG_INDETERMINATE)여야 함")
                .isEqualTo("FCG_INDETERMINATE");
        assertThat(jpaPgOutboxRepository.findByKeyAndTopicInOrderByCreatedAtAsc(
                orderId, List.of(PgTopics.EVENTS_CONFIRMED)))
                .as("발행 행이 정확히 1건이어야 함")
                .hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 격리 — 벤더가 부분 취소로 답하면 전용 사유로 격리(확정 실패로 접지 않음)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("소진 건 — 벤더 조회가 부분 취소로 답하면 부분 취소 사유로 격리되고 발행은 1건")
    void 소진건_부분취소응답_격리되고_사유는_부분취소() {
        // given
        String orderId = "order-dlq-partial-" + UUID.randomUUID();
        String paymentKey = "pay-key-" + orderId;
        Long inboxId = pgInboxRepository.insertPending(orderId, AMOUNT, "TOSS", paymentKey, null);
        pgInboxRepository.transitPendingToInProgress(inboxId);

        doReturn(new PgStatusResult(
                paymentKey, orderId, PgPaymentStatus.PARTIAL_CANCELED,
                BigDecimal.valueOf(AMOUNT), null, null, APPROVED_AT_RAW))
                .when(fakePgGatewayStrategy).getStatusByOrderId(orderId);

        // when
        pgDlqService.handle(buildCommand(orderId, paymentKey));

        // then
        PgInbox settled = pgInboxRepository.findByOrderId(orderId).orElseThrow();
        assertThat(settled.getStatus())
                .as("부분 취소는 확정 실패가 아니라 격리로 가야 함")
                .isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(settled.getReasonCode())
                .as("격리 사유는 부분 취소(FCG_PARTIAL_CANCELED)여야 함")
                .isEqualTo("FCG_PARTIAL_CANCELED");
        assertThat(jpaPgOutboxRepository.findByKeyAndTopicInOrderByCreatedAtAsc(
                orderId, List.of(PgTopics.EVENTS_CONFIRMED)))
                .as("발행 행이 정확히 1건이어야 함")
                .hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 경합 — 대기열 처리(벤더 조회 응답 대기 구간)에 다른 경로가 먼저 종결시키면 중복 발행 없음
    // ─────────────────────────────────────────────────────────────────────────

    @RepeatedTest(10)
    @DisplayName("소진 건 처리 중 다른 경로가 먼저 종결시키면 대기열 경로는 발행 행을 만들지 않는다")
    void 소진건_처리중_다른경로가_먼저_종결시키면_발행행이_생기지_않는다() throws Exception {
        // given — IN_PROGRESS 접수 기록. 좀비 재확인 경로가 정상 확정으로 성공하도록
        // paymentKey 에는 실측 시나리오 접두어를 두지 않는다.
        String orderId = "order-dlq-race-" + UUID.randomUUID();
        String paymentKey = "pay-key-" + orderId;
        Long inboxId = pgInboxRepository.insertPending(orderId, AMOUNT, "TOSS", paymentKey, null);
        pgInboxRepository.transitPendingToInProgress(inboxId);

        // 대기열 경로의 벤더 조회 호출을 latch 로 붙잡아, "조회는 이미 시작됐지만 아직 반영 전"
        // 구간을 결정적으로 연다 — 고정 지연 대신 신호 대기로 그 구간에 다른 경로가 확실히 끼어들게 한다.
        CountDownLatch vendorLookupReached = new CountDownLatch(1);
        CountDownLatch otherPathSettled = new CountDownLatch(1);
        doAnswer(invocation -> {
            vendorLookupReached.countDown();
            awaitWithTimeoutOrFail(otherPathSettled, 5, "다른 경로 종결 대기 시간 초과");
            return new PgStatusResult(
                    paymentKey, orderId, PgPaymentStatus.DONE,
                    BigDecimal.valueOf(AMOUNT), null, null, APPROVED_AT_RAW);
        }).when(fakePgGatewayStrategy).getStatusByOrderId(orderId);

        AtomicReference<Exception> dlqFailure = new AtomicReference<>();
        AtomicReference<Exception> otherPathFailure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // when — 대기열 소비를 먼저 태우고, 벤더 조회 구간에 도달하면 좀비 재확인 경로가
        // 같은 기록을 먼저 승인 종결시킨다(실제 confirm() 호출 + 발행까지 정상 경로 그대로).
        Future<?> dlqHandling = executor.submit(() -> {
            try {
                pgDlqService.handle(buildCommand(orderId, paymentKey));
            } catch (Exception e) {
                dlqFailure.set(e);
            }
        });
        Future<?> otherPath = executor.submit(() -> {
            awaitQuietly(vendorLookupReached);
            try {
                pgInboxProcessUseCase.processInProgressZombie(inboxId);
            } catch (Exception e) {
                otherPathFailure.set(e);
            } finally {
                otherPathSettled.countDown();
            }
        });

        dlqHandling.get(10, TimeUnit.SECONDS);
        otherPath.get(10, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // then — 두 경로 모두 예외 없이 끝나야 한다
        assertThat(dlqFailure.get()).as("대기열 소비 경로는 예외 없이 끝나야 함").isNull();
        assertThat(otherPathFailure.get()).as("좀비 재확인 경로는 예외 없이 끝나야 함").isNull();

        // then — 좀비 재확인 경로가 먼저 승인 종결시켰고, 대기열 경로는 반영 0건 가드로 물러나
        // 발행 행을 추가하지 않는다 — 총 발행 행은 좀비 경로의 1건뿐이다.
        PgInbox settled = pgInboxRepository.findByOrderId(orderId).orElseThrow();
        assertThat(settled.getStatus())
                .as("좀비 재확인 경로가 먼저 승인 종결해야 함")
                .isEqualTo(PgInboxStatus.APPROVED);
        assertThat(jpaPgOutboxRepository.findByKeyAndTopicInOrderByCreatedAtAsc(
                orderId, List.of(PgTopics.EVENTS_CONFIRMED)))
                .as("대기열 경로는 반영 0건 가드에 걸려 발행 행을 만들지 않으므로 좀비 경로의 1건뿐이어야 함")
                .hasSize(1);
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    private PgConfirmCommand buildCommand(String orderId, String paymentKey) {
        return new PgConfirmCommand(
                orderId, paymentKey, BigDecimal.valueOf(AMOUNT), PgVendorType.TOSS,
                UUID.randomUUID().toString());
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기 중 인터럽트", e);
        }
    }

    /**
     * 주어진 latch 가 timeoutSeconds 안에 열리지 않으면 실패시킨다 — 신호를 보낼 스레드가
     * 죽거나 예외로 끝나도 대기 스레드가 무한정 매달리지 않게 한다.
     */
    private void awaitWithTimeoutOrFail(CountDownLatch latch, long timeoutSeconds, String timeoutMessage)
            throws InterruptedException {
        boolean completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            throw new IllegalStateException(timeoutMessage);
        }
    }
}
