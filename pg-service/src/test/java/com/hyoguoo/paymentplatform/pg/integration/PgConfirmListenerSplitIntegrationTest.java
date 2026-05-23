package com.hyoguoo.paymentplatform.pg.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.port.in.PgInboxProcessUseCase;
import com.hyoguoo.paymentplatform.pg.application.port.out.EventDedupeStore;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.service.DuplicateApprovalHandler;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgConfirmResultStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.fake.FakePgGatewayStrategy;
import com.hyoguoo.paymentplatform.pg.infrastructure.repository.JpaPgInboxRepository;
import com.hyoguoo.paymentplatform.pg.mock.FakeEventDedupeStore;
import com.hyoguoo.paymentplatform.pg.presentation.port.PgConfirmCommandService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * listener 책임 분리 + 좀비 회수 acceptance 통합 테스트.
 *
 * <p>검증 범위:
 * <ul>
 *   <li>listener 내 벤더 호출 0: {@code PgConfirmService.handle} 이 벤더를 직접 호출하지 않음</li>
 *   <li>벤더 지연 격리: Mock 벤더 5s 지연 시 handle() latency 에 영향 없음 (워커 처리)</li>
 *   <li>워커 크래시 후 IN_PROGRESS 좀비 회수: processInProgressZombie → terminal 전이</li>
 *   <li>보정 경로 PENDING 우회: DuplicateApprovalHandler.handleDbAbsent* 진입 시 status=PENDING 미경유</li>
 * </ul>
 *
 * <p>인프라:
 * <ul>
 *   <li>Testcontainers MySQL — PgInboxRepositoryImpl SKIP LOCKED 테스트와 동일 패턴</li>
 *   <li>Redis 미사용 — {@code EventDedupeStoreRedisAdapter} {@code @ConditionalOnProperty} 비활성,
 *       {@link IntegrationTestConfig} 의 FakeEventDedupeStore 로 대체</li>
 *   <li>Kafka 미사용 — {@code PaymentConfirmConsumer} {@code @ConditionalOnProperty} 비활성,
 *       {@code PgConfirmCommandService.handle()} 직접 호출</li>
 *   <li>벤더 전략: {@code pg.gateway.type=fake} 로 FakePgGatewayStrategy 활성화 + MockitoSpyBean 오버라이드</li>
 * </ul>
 *
 * <p>컨텍스트 격리:
 * {@code @DirtiesContext(BEFORE_CLASS)} — {@code PgInboxPendingServiceTest.TxIntegrationTestConfig}
 * 가 {@code @Configuration} → {@code @TestConfiguration} 으로 수정되기 전 캐시된 컨텍스트 오염 방지.
 * 현재는 {@code @TestConfiguration} 으로 수정되어 자동 스캔에서 제외됐으므로 불필요하지만,
 * 추후 유사 문제 방지를 위해 유지한다.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@Tag("integration")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@DisplayName("listener 책임 분리 + 좀비 회수 acceptance 통합 테스트")
class PgConfirmListenerSplitIntegrationTest {

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
        // 스케줄러 비활성 — 좀비 폴링이 테스트 흐름을 방해하지 않도록 한다
        registry.add("pg.scheduler.inbox-polling-worker.fixed-delay-ms", () -> "3600000");
        registry.add("pg.scheduler.polling-worker.fixed-delay-ms", () -> "3600000");
        // spring.kafka.bootstrap-servers — 존재하지 않는 서버로 설정
        // KafkaTemplate lazy init 특성상 실제 send() 미호출 시 연결 시도 없음
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9099");
        // Kafka consumer auto-startup 비활성 — 컨텍스트 시작 시 Kafka 연결 시도 방지
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    // ─── TestConfiguration — FakeEventDedupeStore ────────────────────────────

    @TestConfiguration
    static class IntegrationTestConfig {

        /**
         * Redis 없는 환경에서 EventDedupeStore 빈을 FakeEventDedupeStore 로 제공한다.
         * EventDedupeStoreRedisAdapter 는 spring.data.redis.host 미설정 시 비활성이므로
         * 이 빈이 유일한 EventDedupeStore 후보가 된다.
         */
        @Bean
        @Primary
        public EventDedupeStore fakeEventDedupeStore() {
            return new FakeEventDedupeStore();
        }
    }

    // ─── SpyBean / 의존성 ─────────────────────────────────────────────────────

    /**
     * FakePgGatewayStrategy SpyBean — pg.gateway.type=fake 로 활성화된 빈.
     * confirm() / getStatusByOrderId() 를 doReturn/doThrow 로 오버라이드한다.
     */
    @MockitoSpyBean
    private FakePgGatewayStrategy fakePgGatewayStrategy;

    @Autowired
    private PgConfirmCommandService pgConfirmCommandService;

    @Autowired
    private PgInboxRepository pgInboxRepository;

    @Autowired
    private PgInboxProcessUseCase pgInboxProcessUseCase;

    @Autowired
    private DuplicateApprovalHandler duplicateApprovalHandler;

    @Autowired
    private JpaPgInboxRepository jpaPgInboxRepository;

    // ─── 테스트 데이터 상수 ──────────────────────────────────────────────────

    private static final long AMOUNT = 10_000L;

    // ─── setUp / tearDown ─────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        jpaPgInboxRepository.deleteAll();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // listener 내 벤더 호출 0
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("payment.commands.confirm 수신 후 listener 가 벤더(confirm) 호출 안 함")
    void listenerDoesNotCallVendor_whenNewMessage() throws InterruptedException {
        // given
        String orderId = "order-a1-" + UUID.randomUUID();
        PgConfirmCommand command = buildCommand(orderId);

        // when — listener 역할 직접 호출 (PaymentConfirmConsumer 대체)
        pgConfirmCommandService.handle(command, 1);

        // PgInboxImmediateWorker 가 백그라운드에서 processPending 을 실행하므로
        // INSERT 완료 후 채널 offer → 워커 take → processPending 순으로 비동기 실행된다.
        // PENDING 상태가 이미 IN_PROGRESS/APPROVED 로 전이됐을 수 있으므로 status 확인은 생략하고
        // INSERT 자체가 됐는지 (present) 만 확인한다.
        // 단, 워커 스레드가 아직 processPending 을 시작하지 않은 경우 PENDING 을 볼 수 있다.

        // then — FakePgGatewayStrategy.confirm 호출 0회 (벤더 호출은 워커 담당)
        verify(fakePgGatewayStrategy, never()).confirm(any());

        // pg_inbox INSERT 확인 — listener 가 INSERT + ack 까지만 담당했음
        // 워커가 이미 처리해서 APPROVED 로 전이됐을 수도 있으므로 present 여부만 검증
        Optional<PgInbox> inbox = pgInboxRepository.findByOrderId(orderId);
        assertThat(inbox)
                .as("listener 가 handle() 내에서 pg_inbox INSERT 를 완료해야 함")
                .isPresent();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 벤더 지연 격리: listener(handle) latency 에 영향 없음
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Mock 벤더 5s 지연 설정 후 handle() latency < 1s (벤더 지연이 listener 에 영향 없음)")
    void listenerThroughputUnaffectedByVendorDelay() throws Exception {
        // given — 벤더 confirm 호출 시 5s 지연 주입 (워커가 처리하므로 handle 에는 미영향)
        org.mockito.Mockito.doAnswer(invocation -> {
            Thread.sleep(5_000);
            return null;
        }).when(fakePgGatewayStrategy).confirm(any());

        String orderId1 = "order-a2-1-" + UUID.randomUUID();
        String orderId2 = "order-a2-2-" + UUID.randomUUID();
        PgConfirmCommand cmd1 = buildCommand(orderId1);
        PgConfirmCommand cmd2 = buildCommand(orderId2);

        // when — 두 메시지를 handle() 로 처리하는 시간 측정
        long start = System.nanoTime();
        pgConfirmCommandService.handle(cmd1, 1);
        pgConfirmCommandService.handle(cmd2, 1);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // then — listener 두 번 합산 latency < 1s (벤더 5s 지연과 무관, 워커가 비동기 처리)
        assertThat(elapsedMs)
                .as("listener 두 번 합산 latency 가 벤더 지연(5s)보다 현저히 작아야 함")
                .isLessThan(1_000L);

        // PENDING INSERT 두 건 확인 — listener 가 INSERT 까지만 완료
        assertThat(pgInboxRepository.findByOrderId(orderId1)).isPresent();
        assertThat(pgInboxRepository.findByOrderId(orderId2)).isPresent();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 워커 크래시 후 IN_PROGRESS 좀비 회수
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("processPending 중 RuntimeException → IN_PROGRESS 좀비 → processInProgressZombie → terminal 전이")
    void zombieRecovery_afterWorkerCrash_completesProcessing() {
        // given — PENDING 행 INSERT
        String orderId = "order-a3-" + UUID.randomUUID();
        Long inboxId = pgInboxRepository.insertPending(
                orderId, AMOUNT, UUID.randomUUID().toString(), "TOSS", "pay-key-a3");

        // processPending 내 invokeVendor 단계에서 RuntimeException 강제 주입
        // TX_A(PENDING→IN_PROGRESS) 는 이미 커밋됨, TX_B 미진입 → IN_PROGRESS 잔존 (좀비)
        org.mockito.Mockito.doThrow(new RuntimeException("worker-crash-simulated"))
                .when(fakePgGatewayStrategy).confirm(any());

        // when — processPending → TX_A 커밋 후 RuntimeException → IN_PROGRESS 좀비 잔존
        try {
            pgInboxProcessUseCase.processPending(inboxId);
        } catch (RuntimeException ignored) {
            // 예상된 크래시 시뮬레이션
        }

        // IN_PROGRESS 좀비 잔존 확인 (회수 시나리오 전제)
        Optional<PgInbox> zombie = pgInboxRepository.findByOrderId(orderId);
        assertThat(zombie).isPresent();
        assertThat(zombie.get().getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);

        // given — 좀비 회수 시 벤더가 성공 응답 반환하도록 Mock 재설정
        org.mockito.Mockito.doReturn(buildSuccessResult(orderId)).when(fakePgGatewayStrategy).confirm(any());

        // when — processInProgressZombie → 벤더 재호출 → terminal 전이
        pgInboxProcessUseCase.processInProgressZombie(inboxId);

        // then — terminal 상태로 전이 확인
        Optional<PgInbox> recovered = pgInboxRepository.findByOrderId(orderId);
        assertThat(recovered).isPresent();
        assertThat(recovered.get().getStatus().isTerminal())
                .as("processInProgressZombie 후 inbox 가 terminal 상태여야 함")
                .isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 보정 경로 PENDING 우회
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DuplicateApprovalHandler.handleDbAbsentAmountMatch 진입 시 PENDING 미경유, 직접 APPROVED 전이")
    void correctionPath_doesNotGoThroughPending() {
        // given — inbox 행 없음 (absent 상태)
        String orderId = "order-a4-" + UUID.randomUUID();

        // 벤더 상태 조회 결과: DONE + 동일 amount → handleDbAbsentAmountMatch 경로
        PgStatusResult vendorStatus = new PgStatusResult(
                "pay-key-a4",
                orderId,
                PgPaymentStatus.DONE,
                BigDecimal.valueOf(AMOUNT),
                null,
                null
        );
        org.mockito.Mockito.doReturn(vendorStatus)
                .when(fakePgGatewayStrategy).getStatusByOrderId(orderId);

        // when — DuplicateApprovalHandler.handleDuplicateApproval 직접 호출
        // 경로: handleDbAbsent → payloadAmount == vendorAmount → handleDbAbsentAmountMatch
        //       → transitDirectToTerminal(APPROVED) — PENDING 미경유
        duplicateApprovalHandler.handleDuplicateApproval(
                orderId, BigDecimal.valueOf(AMOUNT), PgVendorType.TOSS);

        // then — APPROVED 상태로 직접 전이 (PENDING 미경유)
        Optional<PgInbox> result = pgInboxRepository.findByOrderId(orderId);
        assertThat(result).isPresent();
        assertThat(result.get().getStatus())
                .as("보정 경로 absent+amountMatch: PENDING 우회하여 APPROVED 직접 전이")
                .isEqualTo(PgInboxStatus.APPROVED);
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    private PgConfirmCommand buildCommand(String orderId) {
        return new PgConfirmCommand(
                orderId,
                "pay-key-" + orderId,
                BigDecimal.valueOf(AMOUNT),
                PgVendorType.TOSS,
                UUID.randomUUID().toString()
        );
    }

    private PgConfirmResult buildSuccessResult(String orderId) {
        return new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS,
                "pay-key-" + orderId,
                orderId,
                BigDecimal.valueOf(AMOUNT),
                null,
                null,
                null
        );
    }
}
