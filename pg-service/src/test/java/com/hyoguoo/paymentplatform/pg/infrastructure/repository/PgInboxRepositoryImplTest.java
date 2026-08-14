package com.hyoguoo.paymentplatform.pg.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.infrastructure.config.PgServiceConfig;
import com.hyoguoo.paymentplatform.pg.infrastructure.entity.PgInboxEntity;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;

/**
 * PgInboxRepositoryImpl SKIP LOCKED + 멱등 INSERT 단위 테스트.
 * MySQL Testcontainers 기반 — SKIP LOCKED 동시성 검증 포함.
 *
 * <p>docker-java 기본 API 버전(1.32)이 Docker 29.4.2 최소 지원 버전(1.40)보다 낮아
 * src/test/resources/docker-java.properties 에서 api.version=1.44 로 고정한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PgInboxRepositoryImpl.class, PgServiceConfig.class})
@DisplayName("PgInboxRepositoryImpl — SKIP LOCKED + 멱등 INSERT")
class PgInboxRepositoryImplTest {

    static final MySQLContainer<?> MYSQL_CONTAINER;

    static {
        MYSQL_CONTAINER = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("pg-test")
                .withUsername("test")
                .withPassword("test")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
        MYSQL_CONTAINER.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private PgInboxRepositoryImpl sut;

    @Autowired
    private JpaPgInboxRepository jpaRepository;

    @Autowired
    private Clock clock;

    private static final long AMOUNT = 10_000L;

    @BeforeEach
    void setUp() {
        jpaRepository.deleteAll();
    }

    // ─── insertPending ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("insertPending — 새 orderId 삽입 시 PENDING 행 생성")
    void insertPending_insertsRowWithPendingStatus() {
        // given
        String orderId = "order-pcs4-001";

        // when
        Long inboxId = sut.insertPending(orderId, AMOUNT, "TOSS_PAYMENTS", "pay-key-001", null);

        // then
        assertThat(inboxId).isNotNull().isPositive();
        Optional<PgInboxEntity> saved = jpaRepository.findById(inboxId);
        assertThat(saved).isPresent();
        assertThat(saved.get().getStatus()).isEqualTo(PgInboxStatus.PENDING);
        assertThat(saved.get().getOrderId()).isEqualTo(orderId);
        assertThat(saved.get().getAmount()).isEqualTo(AMOUNT);
    }

    @Test
    @DisplayName("insertPending — 중복 orderId 재호출 시 기존 id 반환, 신규 행 없음")
    void insertPending_duplicateOrderId_returnsExistingId() {
        // given
        String orderId = "order-pcs4-002";
        Long firstId = sut.insertPending(orderId, AMOUNT, "TOSS_PAYMENTS", "pay-key-002", null);

        // when — 동일 orderId 재호출
        Long secondId = sut.insertPending(orderId, AMOUNT, "TOSS_PAYMENTS", "pay-key-002b", null);

        // then — 동일 id 반환, 총 행 수 1개
        assertThat(secondId).isEqualTo(firstId);
        assertThat(jpaRepository.count()).isEqualTo(1);
    }

    /**
     * 유일 제약 동시 삽입 테스트.
     * @Transactional(NOT_SUPPORTED) — 두 스레드가 각자 독립 TX 로 INSERT IGNORE 를 실행해야
     * 서로의 커밋 여부가 실제 유니크 인덱스 경합에 반영된다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("insertPending — 같은 orderId 두 스레드 동시 삽입: 행은 하나만 생기고 같은 식별자를 받는다")
    void 접수삽입_같은주문번호_두스레드_동시삽입_행은_하나만_생기고_같은_식별자를_받는다() throws InterruptedException {
        // given
        String orderId = "order-pcs4-concurrent-insert";
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicReference<Long> firstId = new AtomicReference<>();
        AtomicReference<Long> secondId = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.submit(() -> insertAfterGate(startGate, doneLatch, firstId, orderId, "pay-key-concurrent-1"));
        executor.submit(() -> insertAfterGate(startGate, doneLatch, secondId, orderId, "pay-key-concurrent-2"));

        // when — 두 스레드를 신호로 동시에 푼다
        startGate.countDown();
        doneLatch.await();
        executor.shutdown();

        // then — 동일 id 반환, 총 행 수 1개
        assertThat(firstId.get()).isNotNull();
        assertThat(secondId.get()).isEqualTo(firstId.get());
        assertThat(jpaRepository.count()).isEqualTo(1);

        // cleanup — NOT_SUPPORTED 이므로 @BeforeEach 롤백이 없어 수동 정리
        jpaRepository.deleteAll();
    }

    private void insertAfterGate(CountDownLatch startGate, CountDownLatch doneLatch,
                                 AtomicReference<Long> resultHolder, String orderId, String paymentKey) {
        try {
            startGate.await();
            resultHolder.set(sut.insertPending(orderId, AMOUNT, "TOSS_PAYMENTS", paymentKey, null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            doneLatch.countDown();
        }
    }

    // ─── transitDirectToInProgress ─────────────────────────────────────────────

    @Test
    @DisplayName("transitDirectToInProgress — 삽입 후 조회 → status == IN_PROGRESS")
    void transitDirectToInProgress_insertsRowWithInProgressStatus() {
        // given
        String orderId = "order-pcs4-003";

        // when
        Long inboxId = sut.transitDirectToInProgress(orderId, AMOUNT);

        // then
        assertThat(inboxId).isNotNull().isPositive();
        Optional<PgInboxEntity> saved = jpaRepository.findById(inboxId);
        assertThat(saved).isPresent();
        assertThat(saved.get().getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
        assertThat(saved.get().getOrderId()).isEqualTo(orderId);
    }

    // ─── transitDirectToTerminal ────────────────────────────────────────────────

    @Test
    @DisplayName("transitDirectToTerminal — APPROVED 삽입 후 조회 → status == APPROVED, storedStatusResult 저장")
    void transitDirectToTerminal_approved_insertsApprovedRow() {
        // given
        String orderId = "order-pcs4-004";
        String storedResult = "{\"status\":\"DONE\"}";

        // when
        Long inboxId = sut.transitDirectToTerminal(orderId, AMOUNT, PgInboxStatus.APPROVED, storedResult, null);

        // then
        assertThat(inboxId).isNotNull().isPositive();
        Optional<PgInboxEntity> saved = jpaRepository.findById(inboxId);
        assertThat(saved).isPresent();
        assertThat(saved.get().getStatus()).isEqualTo(PgInboxStatus.APPROVED);
        assertThat(saved.get().getStoredStatusResult()).isEqualTo(storedResult);
    }

    @Test
    @DisplayName("transitDirectToTerminal — QUARANTINED 삽입 후 조회 → status == QUARANTINED")
    void transitDirectToTerminal_quarantined_insertsQuarantinedRow() {
        // given
        String orderId = "order-pcs4-005";

        // when
        Long inboxId = sut.transitDirectToTerminal(orderId, AMOUNT, PgInboxStatus.QUARANTINED, null, "AMOUNT_MISMATCH");

        // then
        assertThat(inboxId).isNotNull().isPositive();
        Optional<PgInboxEntity> saved = jpaRepository.findById(inboxId);
        assertThat(saved).isPresent();
        assertThat(saved.get().getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(saved.get().getReasonCode()).isEqualTo("AMOUNT_MISMATCH");
    }

    // ─── transitPendingToInProgress ────────────────────────────────────────────

    @Test
    @DisplayName("transitPendingToInProgress — PENDING 행 → 호출 후 IN_PROGRESS, return true")
    void transitPendingToInProgress_pendingRow_updatesStatus() {
        // given — PENDING 행 삽입
        String orderId = "order-pcs4-006";
        Long inboxId = sut.insertPending(orderId, AMOUNT, "TOSS_PAYMENTS", "pay-key-006", null);

        // when
        boolean result = sut.transitPendingToInProgress(inboxId);

        // then
        assertThat(result).isTrue();
        Optional<PgInboxEntity> updated = jpaRepository.findById(inboxId);
        assertThat(updated).isPresent();
        assertThat(updated.get().getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("transitPendingToInProgress — IN_PROGRESS 행 → 0 row UPDATE, return false")
    void transitPendingToInProgress_nonPendingRow_returnsFalse() {
        // given — IN_PROGRESS 행 삽입 (PENDING 우회)
        String orderId = "order-pcs4-007";
        Long inboxId = sut.transitDirectToInProgress(orderId, AMOUNT);

        // when
        boolean result = sut.transitPendingToInProgress(inboxId);

        // then — IN_PROGRESS 이므로 전이 실패
        assertThat(result).isFalse();
        Optional<PgInboxEntity> stillInProgress = jpaRepository.findById(inboxId);
        assertThat(stillInProgress).isPresent();
        assertThat(stillInProgress.get().getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
    }

    /**
     * SKIP LOCKED 동시성 테스트.
     * @Transactional(NOT_SUPPORTED) — @DataJpaTest 의 외부 트랜잭션을 비활성화하여
     * 워커 스레드가 커밋된 PENDING 행을 실제로 볼 수 있도록 한다.
     * 외부 트랜잭션 안에서 insertPending 을 호출하면 워커가 미커밋 행을 못 보므로 trueCount=0 이 된다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("transitPendingToInProgress — SKIP LOCKED: 동시 워커 2개 중 하나만 true 반환")
    void transitPendingToInProgress_skipLocked_concurrentWorkerSeesEmpty() throws InterruptedException {
        // given — PENDING 행 삽입 + 자체 TX 커밋 (NOT_SUPPORTED 이므로 insertPending 의 @Transactional 이 독립 TX)
        String orderId = "order-pcs4-skip-locked";
        Long inboxId = sut.insertPending(orderId, AMOUNT, "TOSS_PAYMENTS", "pay-key-skip", null);

        // when — 워커 2개가 동시에 동일 PENDING row를 선점 시도
        AtomicInteger trueCount = new AtomicInteger(0);
        AtomicInteger falseCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            final Long targetId = inboxId;
            executor.submit(() -> {
                try {
                    boolean acquired = sut.transitPendingToInProgress(targetId);
                    if (acquired) {
                        trueCount.incrementAndGet();
                    } else {
                        falseCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // then — 정확히 1개만 true, 1개는 false (SKIP LOCKED 원자성)
        assertThat(trueCount.get()).isEqualTo(1);
        assertThat(falseCount.get()).isEqualTo(1);
        // 최종 상태 IN_PROGRESS
        Optional<PgInboxEntity> finalState = jpaRepository.findById(inboxId);
        assertThat(finalState).isPresent();
        assertThat(finalState.get().getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);

        // cleanup — NOT_SUPPORTED 이므로 @BeforeEach 롤백이 없어 수동 정리
        jpaRepository.deleteAll();
    }

    // ─── findPendingZombieIds ───────────────────────────────────────────────────

    @Test
    @DisplayName("findPendingZombieIds — received_at < cutoff 인 PENDING 행 id 반환")
    void findPendingZombieIds_returnsExpiredRows() {
        // given — 오래된 PENDING 행 2개 (created_at 을 과거로 직접 저장)
        LocalDateTime longAgo = LocalDateTime.now(clock).minusMinutes(10);
        PgInboxEntity old1 = buildPendingEntity("order-zombie-p1", longAgo);
        PgInboxEntity old2 = buildPendingEntity("order-zombie-p2", longAgo);
        // 새 PENDING 행 1개 — cutoff 이내 (현재 시각)
        PgInboxEntity fresh = buildPendingEntity("order-zombie-p3", LocalDateTime.now(clock));
        jpaRepository.saveAll(List.of(old1, old2, fresh));

        // when — threshold = 5분 (300_000ms)
        long thresholdMs = 5 * 60 * 1000L;
        List<Long> zombieIds = sut.findPendingZombieIds(10, thresholdMs);

        // then — 오래된 2개만 반환
        assertThat(zombieIds).hasSize(2);
        assertThat(zombieIds).doesNotContain(fresh.getId());
    }

    // ─── findInProgressZombieIds ────────────────────────────────────────────────

    @Test
    @DisplayName("findInProgressZombieIds — updated_at < cutoff 인 IN_PROGRESS 행 id 반환")
    void findInProgressZombieIds_returnsExpiredRows() {
        // given — 오래된 IN_PROGRESS 행 2개
        LocalDateTime longAgo = LocalDateTime.now(clock).minusMinutes(10);
        PgInboxEntity old1 = buildInProgressEntity("order-zombie-ip1", longAgo);
        PgInboxEntity old2 = buildInProgressEntity("order-zombie-ip2", longAgo);
        // 새 IN_PROGRESS 행 1개
        PgInboxEntity fresh = buildInProgressEntity("order-zombie-ip3", LocalDateTime.now(clock));
        jpaRepository.saveAll(List.of(old1, old2, fresh));

        // when — threshold = 5분 (300_000ms)
        long thresholdMs = 5 * 60 * 1000L;
        List<Long> zombieIds = sut.findInProgressZombieIds(10, thresholdMs);

        // then — 오래된 2개만 반환
        assertThat(zombieIds).hasSize(2);
        assertThat(zombieIds).doesNotContain(fresh.getId());
    }

    // ─── selectInProgressForUpdateSkipLocked ────────────────────────────────────

    @Test
    @DisplayName("selectInProgressForUpdateSkipLocked — IN_PROGRESS 행 → Optional.of(inbox) 반환")
    void selectInProgressForUpdateSkipLocked_inProgressRow_returnsInbox() {
        // given — IN_PROGRESS 행 삽입
        String orderId = "order-skip-ip-001";
        Long inboxId = sut.transitDirectToInProgress(orderId, AMOUNT);

        // when
        Optional<com.hyoguoo.paymentplatform.pg.domain.PgInbox> result =
                sut.selectInProgressForUpdateSkipLocked(inboxId);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getOrderId()).isEqualTo(orderId);
    }

    @Test
    @DisplayName("selectInProgressForUpdateSkipLocked — PENDING 행 → Optional.empty() 반환 (상태 조건 불일치)")
    void selectInProgressForUpdateSkipLocked_pendingRow_returnsEmpty() {
        // given — PENDING 행 삽입
        String orderId = "order-skip-pending-001";
        Long inboxId = sut.insertPending(orderId, AMOUNT, "TOSS_PAYMENTS", "pk-skip-p", null);

        // when
        Optional<com.hyoguoo.paymentplatform.pg.domain.PgInbox> result =
                sut.selectInProgressForUpdateSkipLocked(inboxId);

        // then — PENDING 이므로 IN_PROGRESS 조건 불일치 → empty
        assertThat(result).isEmpty();
    }

    /**
     * SKIP LOCKED 동시성 테스트 — IN_PROGRESS 경로.
     * TX_lock 이 보유 중일 때 두 번째 호출이 빈 결과를 반환하는지 검증.
     * NOT_SUPPORTED: @DataJpaTest 외부 트랜잭션 비활성화로 독립 TX 커밋 가시성 확보.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("selectInProgressForUpdateSkipLocked_lockHeld_returnsEmpty — 동시 두 TX 중 하나만 row 획득")
    void selectInProgressForUpdateSkipLocked_lockHeld_returnsEmpty() throws InterruptedException {
        // given — IN_PROGRESS 행 삽입 + 자체 TX 커밋
        String orderId = "order-skip-ip-concurrent";
        Long inboxId = sut.transitDirectToInProgress(orderId, AMOUNT);

        // when — 워커 2개가 동시에 동일 IN_PROGRESS row 를 SKIP LOCKED 선점 시도
        AtomicInteger presentCount = new AtomicInteger(0);
        AtomicInteger emptyCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        for (int i = 0; i < 2; i++) {
            final Long targetId = inboxId;
            executor.submit(() -> {
                try {
                    Optional<com.hyoguoo.paymentplatform.pg.domain.PgInbox> result =
                            sut.selectInProgressForUpdateSkipLocked(targetId);
                    if (result.isPresent()) {
                        presentCount.incrementAndGet();
                    } else {
                        emptyCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // then — 정확히 1개만 row 획득, 1개는 빈 결과 (SKIP LOCKED 원자성)
        assertThat(presentCount.get()).isEqualTo(1);
        assertThat(emptyCount.get()).isEqualTo(1);

        // cleanup
        jpaRepository.deleteAll();
    }

    // ─── transitToApproved / transitToFailed ───────────────────────────────────

    @Test
    @DisplayName("transitToApproved — IN_PROGRESS 행 → 1건 반영, status APPROVED")
    void transitToApproved_inProgressRow_returnsOneAndUpdatesStatus() {
        // given
        String orderId = "order-pcs4-approve-001";
        sut.transitDirectToInProgress(orderId, AMOUNT);
        String storedResult = "{\"status\":\"DONE\"}";

        // when
        int updated = sut.transitToApproved(orderId, storedResult);

        // then
        assertThat(updated).isEqualTo(1);
        Optional<PgInboxEntity> saved = jpaRepository.findByOrderId(orderId);
        assertThat(saved).isPresent();
        assertThat(saved.get().getStatus()).isEqualTo(PgInboxStatus.APPROVED);
        assertThat(saved.get().getStoredStatusResult()).isEqualTo(storedResult);
    }

    @Test
    @DisplayName("transitToApproved — 이미 종결(APPROVED) 행 → 0건 반영, 재전이 없음")
    void transitToApproved_alreadyTerminalRow_returnsZero() {
        // given
        String orderId = "order-pcs4-approve-002";
        sut.transitDirectToTerminal(orderId, AMOUNT, PgInboxStatus.APPROVED, "{\"status\":\"DONE\"}", null);

        // when
        int updated = sut.transitToApproved(orderId, "{\"status\":\"DONE2\"}");

        // then — 0건 반영, 기존 저장 결과 유지
        assertThat(updated).isEqualTo(0);
        Optional<PgInboxEntity> saved = jpaRepository.findByOrderId(orderId);
        assertThat(saved).isPresent();
        assertThat(saved.get().getStoredStatusResult()).isEqualTo("{\"status\":\"DONE\"}");
    }

    @Test
    @DisplayName("transitToFailed — IN_PROGRESS 행 → 1건 반영, status FAILED")
    void transitToFailed_inProgressRow_returnsOneAndUpdatesStatus() {
        // given
        String orderId = "order-pcs4-fail-001";
        sut.transitDirectToInProgress(orderId, AMOUNT);
        String storedResult = "{\"status\":\"ABORTED\"}";

        // when
        int updated = sut.transitToFailed(orderId, storedResult, "FCG_CONFIRMED_FAILED");

        // then
        assertThat(updated).isEqualTo(1);
        Optional<PgInboxEntity> saved = jpaRepository.findByOrderId(orderId);
        assertThat(saved).isPresent();
        assertThat(saved.get().getStatus()).isEqualTo(PgInboxStatus.FAILED);
        assertThat(saved.get().getReasonCode()).isEqualTo("FCG_CONFIRMED_FAILED");
    }

    @Test
    @DisplayName("transitToFailed — 이미 종결(FAILED) 행 → 0건 반영, 재전이 없음")
    void transitToFailed_alreadyTerminalRow_returnsZero() {
        // given
        String orderId = "order-pcs4-fail-002";
        sut.transitDirectToTerminal(
                orderId, AMOUNT, PgInboxStatus.FAILED, "{\"status\":\"ABORTED\"}", "ORIGINAL_REASON");

        // when
        int updated = sut.transitToFailed(orderId, "{\"status\":\"CANCELED\"}", "NEW_REASON");

        // then — 0건 반영, 기존 사유 코드 유지
        assertThat(updated).isEqualTo(0);
        Optional<PgInboxEntity> saved = jpaRepository.findByOrderId(orderId);
        assertThat(saved).isPresent();
        assertThat(saved.get().getReasonCode()).isEqualTo("ORIGINAL_REASON");
    }

    // ─── 헬퍼 메서드 ────────────────────────────────────────────────────────────

    private PgInboxEntity buildPendingEntity(String orderId, LocalDateTime createdAt) {
        return PgInboxEntity.builder()
                .orderId(orderId)
                .status(PgInboxStatus.PENDING)
                .amount(AMOUNT)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private PgInboxEntity buildInProgressEntity(String orderId, LocalDateTime updatedAt) {
        return PgInboxEntity.builder()
                .orderId(orderId)
                .status(PgInboxStatus.IN_PROGRESS)
                .amount(AMOUNT)
                .createdAt(updatedAt)
                .updatedAt(updatedAt)
                .build();
    }
}
