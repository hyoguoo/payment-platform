package com.hyoguoo.paymentplatform.pg.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.application.port.in.PgInboxProcessUseCase;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.service.DuplicateApprovalHandler;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.fake.FakePgGatewayStrategy;
import com.hyoguoo.paymentplatform.pg.infrastructure.repository.JpaPgInboxRepository;
import com.hyoguoo.paymentplatform.pg.infrastructure.repository.JpaPgOutboxRepository;
import com.hyoguoo.paymentplatform.pg.infrastructure.scheduler.PgOutboxPollingWorker;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 중복 승인 응답 종결 + 좀비 회수 통합 검증 — 겹침과 실제 DB 경합까지 포함한다.
 *
 * <p>검증 범위:
 * <ul>
 *   <li>좀비 회수 시 벤더 승인 완료를 확인하면 접수 기록이 승인으로 종결되고, 반복 회수에도
 *       발행이 늘지 않는다(이미 종결된 행은 SKIP LOCKED 의 IN_PROGRESS 조건에 걸리지 않는다).</li>
 *   <li>겹친 두 호출은 모의 벤더의 처리 중 거부(Task 7/8)로 재현되고, 종결과 발행이 각 1회다.</li>
 *   <li>전이가 0건이면(이미 종결) 발행 행 자체가 생기지 않아 폴링 안전망도 발행할 것이 없다.</li>
 *   <li>같은 주문에 승인 종결과 격리 종결을 실제 스레드로 동시에 걸어도, CAS 0건 가드가 정확히
 *       하나로 수렴시킨다 — 이 마지막 케이스가 이 토픽 전체의 핵심 안전장치다.</li>
 * </ul>
 *
 * <p>인프라는 {@code PgConfirmListenerSplitIntegrationTest} 와 동일 패턴을 따른다 — Testcontainers
 * MySQL + {@code pg.gateway.type=fake} + 스케줄러/Kafka 비활성 + {@code FakePgGatewayStrategy}
 * {@code @MockitoSpyBean} 오버라이드.
 *
 * <p>승인 종결과 격리 종결을 동시에 만드는 마지막 케이스는 Fake 저장소가 아니라 실제 CAS 경합을
 * 재현해야 하므로 Testcontainers 필수다 — {@link DuplicateApprovalHandler#handleDuplicateApproval}
 * (격리 분기는 종결 여부를 먼저 확인하지 않고 항상 CAS 를 시도한다)과
 * {@link PgInboxProcessUseCase#processInProgressZombie}(좀비 재확인 승인 경로)를 각각 별도
 * 스레드에서 같은 주문에 동시에 걸어, {@code transitToApproved} / {@code transitToQuarantined}
 * 두 CAS 가 같은 행을 두고 실제로 경합하게 만든다.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@Tag("integration")
@Testcontainers
@ActiveProfiles("test")
@DisplayName("중복 승인 종결 + 좀비 회수 통합 검증")
class PgDuplicateApprovalSettlementIntegrationTest {

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
    private PgInboxProcessUseCase pgInboxProcessUseCase;

    @Autowired
    private PgInboxRepository pgInboxRepository;

    @Autowired
    private DuplicateApprovalHandler duplicateApprovalHandler;

    @Autowired
    private JpaPgInboxRepository jpaPgInboxRepository;

    @Autowired
    private JpaPgOutboxRepository jpaPgOutboxRepository;

    @Autowired
    private PgOutboxPollingWorker pgOutboxPollingWorker;

    // ─── 테스트 데이터 상수 ──────────────────────────────────────────────────

    private static final long AMOUNT = 10_000L;
    private static final String APPROVED_AT_RAW = "2026-08-12T00:00:00Z";

    @BeforeEach
    void setUp() {
        jpaPgInboxRepository.deleteAll();
        jpaPgOutboxRepository.deleteAll();
        // 이전 테스트가 지연을 주입했을 수 있으므로 매 테스트 시작 전 원복한다.
        ReflectionTestUtils.setField(fakePgGatewayStrategy, "latencyMinMillis", 0L);
        ReflectionTestUtils.setField(fakePgGatewayStrategy, "latencyMaxMillis", 0L);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 좀비 회수 — 벤더 승인 완료 확인 시 승인 종결 + 발행 1건, 반복 회수에도 불변
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("좀비 회수 — 벤더 승인 완료 확인 시 접수 기록이 승인으로 종결되고 발행은 1건, 반복 회수에도 늘지 않는다")
    void 좀비회수_벤더승인완료_접수기록이_승인으로_종결되고_발행_1건() {
        // given — PENDING 삽입 후 워커 크래시로 IN_PROGRESS 좀비 잔존. TX_A(PENDING→IN_PROGRESS)는
        // 이미 커밋됐고, 벤더 호출 단계에서 예외가 나 TX_B 는 진입하지 못한다 — 지금은 롤백되는 자리.
        String orderId = "order-zombie-settle-" + UUID.randomUUID();
        String paymentKey = "pay-key-" + orderId;
        Long inboxId = pgInboxRepository.insertPending(orderId, AMOUNT, "TOSS", paymentKey, null);

        doThrow(new RuntimeException("worker-crash-simulated")).when(fakePgGatewayStrategy).confirm(any());
        assertThatThrownBy(() -> pgInboxProcessUseCase.processPending(inboxId))
                .as("예상된 크래시 시뮬레이션 — worker-crash-simulated 예외가 그대로 전파돼야 함")
                .isInstanceOf(RuntimeException.class);

        PgInbox zombie = pgInboxRepository.findByOrderId(orderId).orElseThrow();
        assertThat(zombie.getStatus())
                .as("TX_B 미진입으로 IN_PROGRESS 좀비가 잔존해야 함")
                .isEqualTo(PgInboxStatus.IN_PROGRESS);

        // when — 좀비 회수 시 벤더 재호출이 실제로는 승인 완료 상태를 반환한다.
        doCallRealMethod().when(fakePgGatewayStrategy).confirm(any());
        pgInboxProcessUseCase.processInProgressZombie(inboxId);

        // then — 승인 종결 + 발행 1건
        PgInbox settled = pgInboxRepository.findByOrderId(orderId).orElseThrow();
        assertThat(settled.getStatus())
                .as("좀비 회수 후 벤더 승인 확인 시 접수 기록이 승인으로 종결돼야 함")
                .isEqualTo(PgInboxStatus.APPROVED);
        assertThat(jpaPgOutboxRepository.findByKeyAndTopicInOrderByCreatedAtAsc(
                orderId, List.of(PgTopics.EVENTS_CONFIRMED)))
                .as("발행 행이 정확히 1건이어야 함")
                .hasSize(1);

        // when — 반복 회수(이미 종결된 row 는 SKIP LOCKED 의 IN_PROGRESS 조건에 걸리지 않아 재조회되지 않는다)
        pgInboxProcessUseCase.processInProgressZombie(inboxId);
        pgInboxProcessUseCase.processInProgressZombie(inboxId);

        // then — 발행 행 수는 그대로 1건
        assertThat(jpaPgOutboxRepository.findByKeyAndTopicInOrderByCreatedAtAsc(
                orderId, List.of(PgTopics.EVENTS_CONFIRMED)))
                .as("반복 회수에도 발행 행이 늘지 않아야 함")
                .hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 겹친 두 호출 — 모의 벤더의 처리 중 거부로 재현, 종결과 발행이 각 1회
    // ─────────────────────────────────────────────────────────────────────────

    @RepeatedTest(10)
    @DisplayName("겹친 두 호출 — 원 호출 응답 대기 중 겹친 재호출은 처리 중 거부, 종결과 발행이 각 1회")
    void 겹친_두호출_종결과_발행이_각_1회() throws Exception {
        // given
        String orderId = "order-concurrent-call-" + UUID.randomUUID();
        String paymentKey = "pay-key-" + orderId;
        Long inboxId = pgInboxRepository.insertPending(orderId, AMOUNT, "TOSS", paymentKey, null);
        boolean acquired = pgInboxRepository.transitPendingToInProgress(inboxId);
        assertThat(acquired).as("given 상태 설정 확인").isTrue();

        // 원 호출의 벤더 응답을 인위로 지연시켜, 그 구간에 겹친 재호출이 확실히 처리 중 거부를 맞게 한다.
        ReflectionTestUtils.setField(fakePgGatewayStrategy, "latencyMinMillis", 200L);
        ReflectionTestUtils.setField(fakePgGatewayStrategy, "latencyMaxMillis", 200L);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // when — 원 호출을 먼저 태우고, 벤더 latency 구간(inProgressOrders 등록 완료 이후)에
        // 겹치도록 짧게 대기한 뒤 재호출한다.
        Future<?> firstCall = executor.submit(() -> pgInboxProcessUseCase.processInProgressZombie(inboxId));
        Thread.sleep(80);
        pgInboxProcessUseCase.processInProgressZombie(inboxId);

        firstCall.get(5, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // then — 정확히 한 쪽만 승인 종결되고, 발행은 1건
        PgInbox settled = pgInboxRepository.findByOrderId(orderId).orElseThrow();
        assertThat(settled.getStatus())
                .as("원 호출이 승인 종결해야 함")
                .isEqualTo(PgInboxStatus.APPROVED);
        assertThat(jpaPgOutboxRepository.findByKeyAndTopicInOrderByCreatedAtAsc(
                orderId, List.of(PgTopics.EVENTS_CONFIRMED)))
                .as("종결과 발행이 각 1회여야 함 — 겹친 재호출은 시도횟수·상태를 건드리지 않는다")
                .hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 전이 0건 — 폴링 안전망도 발행하지 않는다
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("전이 0건 — 이미 종결된 기록에 뒤늦게 도착한 신호는 발행 행을 만들지 않고, 폴링 안전망도 발행할 것이 없다")
    void 전이_0건이면_폴링_안전망도_발행하지_않는다() {
        // given — 이미 종결(APPROVED)된 기록
        String orderId = "order-guard-late-signal-" + UUID.randomUUID();
        String paymentKey = "pay-key-" + orderId;
        pgInboxRepository.transitDirectToTerminal(
                orderId, AMOUNT, PgInboxStatus.APPROVED, "{\"status\":\"DONE\"}", null);

        // 벤더 상태 조회 결과를 금액 불일치로 고정한다 — 이 분기(handleAmountMismatchDbExists)는
        // 종결 여부를 먼저 확인하지 않고 항상 transitToQuarantined CAS 를 시도하므로, 이미 종결된
        // 행에 대한 0건 가드를 재현하기에 적합하다.
        doReturn(new PgStatusResult(
                paymentKey, orderId, PgPaymentStatus.DONE,
                BigDecimal.valueOf(AMOUNT + 1_000L), null, null, APPROVED_AT_RAW))
                .when(fakePgGatewayStrategy).getStatusByOrderId(orderId);

        // when — 뒤늦게 도착한 중복 승인 신호가 이미 종결된 기록을 다시 건드리려 시도한다
        duplicateApprovalHandler.handleDuplicateApproval(orderId, BigDecimal.valueOf(AMOUNT), PgVendorType.TOSS);

        // then — 전이 0건이라 발행 행이 없다
        assertThat(jpaPgOutboxRepository.findByKeyAndTopicInOrderByCreatedAtAsc(
                orderId, List.of(PgTopics.EVENTS_CONFIRMED)))
                .as("전이 0건이면 발행 행 자체가 생기지 않아야 함")
                .isEmpty();

        // then — 폴링 안전망이 훑어도 발행할 것이 없다
        pgOutboxPollingWorker.poll();
        assertThat(jpaPgOutboxRepository.count())
                .as("폴링 안전망도 뒤늦은 발행을 만들어내지 않아야 함")
                .isZero();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 실제 DB 경합 — 승인 종결과 격리 종결을 동시에, 발행 1건 + 상태 수렴
    // ─────────────────────────────────────────────────────────────────────────

    @RepeatedTest(10)
    @DisplayName("동일 주문에 승인 종결과 격리 종결을 실제 스레드로 동시에 걸어도 발행은 1건, 상태는 하나로 수렴한다")
    void 동일주문_두스레드_동시종결_발행1건_상태수렴() throws Exception {
        // given — IN_PROGRESS 접수 기록
        String orderId = "order-race-" + UUID.randomUUID();
        String paymentKey = "pay-key-" + orderId;
        Long inboxId = pgInboxRepository.insertPending(orderId, AMOUNT, "TOSS", paymentKey, null);
        boolean acquired = pgInboxRepository.transitPendingToInProgress(inboxId);
        assertThat(acquired).as("given 상태 설정 확인").isTrue();

        // 격리 종결 스레드가 항상 금액 불일치 분기(handleAmountMismatchDbExists)를 타도록 벤더 조회
        // 결과를 고정한다 — 이 분기는 종결 여부를 먼저 확인하지 않고 항상 CAS 를 시도하므로, 승인
        // 종결 스레드(processInProgressZombie)와 실제 DB 행에서 경합한다.
        doReturn(new PgStatusResult(
                paymentKey, orderId, PgPaymentStatus.DONE,
                BigDecimal.valueOf(AMOUNT + 1_000L), null, null, APPROVED_AT_RAW))
                .when(fakePgGatewayStrategy).getStatusByOrderId(orderId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Exception> approvalFailure = new AtomicReference<>();
        AtomicReference<Exception> quarantineFailure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // when — 승인 종결(좀비 재확인)과 격리 종결(중복 승인 방어)을 같은 시점에 겹친다.
        executor.submit(() -> {
            ready.countDown();
            awaitQuietly(start);
            try {
                pgInboxProcessUseCase.processInProgressZombie(inboxId);
            } catch (Exception e) {
                approvalFailure.set(e);
            }
        });
        executor.submit(() -> {
            ready.countDown();
            awaitQuietly(start);
            try {
                duplicateApprovalHandler.handleDuplicateApproval(
                        orderId, BigDecimal.valueOf(AMOUNT), PgVendorType.TOSS);
            } catch (Exception e) {
                quarantineFailure.set(e);
            }
        });

        ready.await();
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // then — 두 경로 모두 예외 없이 종료돼야 한다 — 진 쪽은 0건 가드로 조용히 물러난다.
        assertThat(approvalFailure.get()).as("승인 종결 경로는 예외 없이 끝나야 함").isNull();
        assertThat(quarantineFailure.get()).as("격리 종결 경로는 예외 없이 끝나야 함").isNull();

        // then — 최종 상태는 APPROVED 또는 QUARANTINED 중 하나로 수렴하고, 발행은 정확히 1건이다.
        PgInbox settled = pgInboxRepository.findByOrderId(orderId).orElseThrow();
        assertThat(settled.getStatus())
                .as("승인 또는 격리 중 하나로 수렴해야 함 — IN_PROGRESS 로 남지 않는다")
                .isIn(PgInboxStatus.APPROVED, PgInboxStatus.QUARANTINED);
        assertThat(jpaPgOutboxRepository.findByKeyAndTopicInOrderByCreatedAtAsc(
                orderId, List.of(PgTopics.EVENTS_CONFIRMED)))
                .as("진 쪽은 0건 가드로 발행 행을 만들지 않아 정확히 1건이어야 함")
                .hasSize(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 실제 DB 경합 — 승인 종결과 벤더 조회 실패(물러남 가드)를 동시에, 발행 1건 + 상태 수렴
    // ─────────────────────────────────────────────────────────────────────────

    @RepeatedTest(10)
    @DisplayName("동일 주문에 승인 종결과 벤더 조회 실패 격리 전이를 실제 스레드로 동시에 걸어도 발행은 1건, 상태는 하나로 수렴한다")
    void 동일주문_두스레드_승인종결과_벤더조회실패_동시경합_발행1건_상태수렴() throws Exception {
        // given — IN_PROGRESS 접수 기록
        String orderId = "order-race-indeterminate-" + UUID.randomUUID();
        String paymentKey = "pay-key-" + orderId;
        Long inboxId = pgInboxRepository.insertPending(orderId, AMOUNT, "TOSS", paymentKey, null);
        boolean acquired = pgInboxRepository.transitPendingToInProgress(inboxId);
        assertThat(acquired).as("given 상태 설정 확인").isTrue();

        // 물러남 가드 스레드가 항상 벤더 조회 실패 분기(handleVendorIndeterminate)를 타도록
        // getStatusByOrderId 를 실패시킨다 — 승인 종결 스레드(processInProgressZombie)가 먼저
        // 종결시키면, 이 스레드는 "기록 있음 + 이미 종결" 상태로 handleVendorIndeterminate 에
        // 진입해 격리 전이(transitToQuarantined)를 시도하고, 반환값 가드가 이를 막아야 한다.
        doThrow(PgGatewayRetryableException.of("vendor lookup failed - race simulation"))
                .when(fakePgGatewayStrategy).getStatusByOrderId(orderId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Exception> approvalFailure = new AtomicReference<>();
        AtomicReference<Exception> indeterminateFailure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // when — 승인 종결(좀비 재확인)과 벤더 조회 실패 처리(중복 승인 방어)를 같은 시점에 겹친다.
        executor.submit(() -> {
            ready.countDown();
            awaitQuietly(start);
            try {
                pgInboxProcessUseCase.processInProgressZombie(inboxId);
            } catch (Exception e) {
                approvalFailure.set(e);
            }
        });
        executor.submit(() -> {
            ready.countDown();
            awaitQuietly(start);
            // 승인 종결 스레드가 SELECT FOR UPDATE + 벤더 확인 + CAS + outbox INSERT + commit 을
            // 끝낼 여유를 준다 — 그래야 이 스레드의 findByOrderId 가 "이미 종결" 상태를 실제로
            // 읽어 이 finding 이 지목한 자리(종결된 기록에 대한 격리 전이 시도)를 재현한다.
            sleepQuietly(20);
            try {
                duplicateApprovalHandler.handleDuplicateApproval(
                        orderId, BigDecimal.valueOf(AMOUNT), PgVendorType.TOSS);
            } catch (Exception e) {
                indeterminateFailure.set(e);
            }
        });

        ready.await();
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // then — 두 경로 모두 예외 없이 종료돼야 한다 — 진 쪽은 0건 가드 또는 물러남으로 조용히 넘어간다.
        assertThat(approvalFailure.get()).as("승인 종결 경로는 예외 없이 끝나야 함").isNull();
        assertThat(indeterminateFailure.get()).as("벤더 조회 실패 경로는 예외 없이 끝나야 함").isNull();

        // then — 최종 상태는 승인 종결로 수렴하고, 발행은 정확히 1건이다 — 접수대장과 발행이
        // 갈라지는 일이 없어야 한다(이 finding 이 막으려던 자리).
        PgInbox settled = pgInboxRepository.findByOrderId(orderId).orElseThrow();
        assertThat(settled.getStatus())
                .as("벤더 조회 실패 경로는 상태를 바꾸지 않으므로 승인 종결로 수렴해야 함")
                .isEqualTo(PgInboxStatus.APPROVED);
        assertThat(jpaPgOutboxRepository.findByKeyAndTopicInOrderByCreatedAtAsc(
                orderId, List.of(PgTopics.EVENTS_CONFIRMED)))
                .as("벤더 조회 실패 경로는 0건 가드로 발행 행을 만들지 않아 정확히 1건이어야 함")
                .hasSize(1);
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기 중 인터럽트", e);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기 중 인터럽트", e);
        }
    }
}
