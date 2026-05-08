package com.hyoguoo.paymentplatform.pg.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.infrastructure.config.PgServiceConfig;
import com.hyoguoo.paymentplatform.pg.infrastructure.entity.PgInboxEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PgInboxRepositoryImpl SKIP LOCKED + 멱등 INSERT 단위 테스트.
 * MySQL Testcontainers 기반 — SKIP LOCKED 동시성 검증 포함.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PgInboxRepositoryImpl.class, PgServiceConfig.class})
@DisplayName("PgInboxRepositoryImpl — SKIP LOCKED + 멱등 INSERT")
class PgInboxRepositoryImplTest {

    @Container
    static final MySQLContainer<?> MYSQL_CONTAINER = new MySQLContainer<>("mysql:8.0")
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
        Long inboxId = sut.insertPending(orderId, AMOUNT, "evt-uuid-001", "TOSS_PAYMENTS", "pay-key-001");

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
        Long firstId = sut.insertPending(orderId, AMOUNT, "evt-uuid-002", "TOSS_PAYMENTS", "pay-key-002");

        // when — 동일 orderId 재호출
        Long secondId = sut.insertPending(orderId, AMOUNT, "evt-uuid-002b", "TOSS_PAYMENTS", "pay-key-002b");

        // then — 동일 id 반환, 총 행 수 1개
        assertThat(secondId).isEqualTo(firstId);
        assertThat(jpaRepository.count()).isEqualTo(1);
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
        Long inboxId = sut.insertPending(orderId, AMOUNT, "evt-uuid-006", "TOSS_PAYMENTS", "pay-key-006");

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

    @Test
    @DisplayName("transitPendingToInProgress — SKIP LOCKED: 동시 워커 2개 중 하나만 true 반환")
    void transitPendingToInProgress_skipLocked_concurrentWorkerSeesEmpty() throws InterruptedException {
        // given — PENDING 행 1개
        String orderId = "order-pcs4-skip-locked";
        Long inboxId = sut.insertPending(orderId, AMOUNT, "evt-uuid-skip", "TOSS_PAYMENTS", "pay-key-skip");

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
