package com.hyoguoo.paymentplatform.pg.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.pg.application.port.out.EventDedupeStore;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.infrastructure.entity.PgInboxEntity;
import com.hyoguoo.paymentplatform.pg.infrastructure.repository.JpaPgInboxRepository;
import com.hyoguoo.paymentplatform.pg.mock.FakeEventDedupeStore;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code JpaPgInboxRepository.incrementAttempt} 진행 중 상태 가드 통합 테스트.
 *
 * <p>{@code @Query} 를 통한 relative increment UPDATE 라 실제 DB 검증이 필요하다
 * ({@link PgInboxTraceparentIntegrationTest} 의 Testcontainers 패턴을 따른다).
 *
 * <p>검증 범위: 종결(APPROVED/FAILED/QUARANTINED) 행에는 시도 횟수 증가가 적용되지 않아야
 * 한다 — 종결 이후 뒤늦게 도착하는 재시도 신호가 종결 시각(updated_at)을 밀어내면 안 된다.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@Tag("integration")
@Testcontainers
@ActiveProfiles("test")
@Transactional
@DisplayName("JpaPgInboxRepository.incrementAttempt 진행 중 상태 가드 통합 테스트")
class PgInboxAttemptGuardIntegrationTest {

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
        registry.add("pg.gateway.type", () -> "fake");
        // 스케줄러 비활성
        registry.add("pg.scheduler.inbox-polling-worker.fixed-delay-ms", () -> "3600000");
        registry.add("pg.scheduler.polling-worker.fixed-delay-ms", () -> "3600000");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9099");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    // ─── TestConfiguration — FakeEventDedupeStore ────────────────────────────

    @TestConfiguration
    static class AttemptGuardIntegrationTestConfig {

        @Bean
        @Primary
        public EventDedupeStore fakeEventDedupeStore() {
            return new FakeEventDedupeStore();
        }
    }

    // ─── 의존성 ───────────────────────────────────────────────────────────────

    @Autowired
    private JpaPgInboxRepository jpaPgInboxRepository;

    private static final long AMOUNT = 10_000L;
    private static final LocalDateTime FIXED_UPDATED_AT = LocalDateTime.parse("2026-04-24T00:00:00");
    private static final LocalDateTime INCREMENT_NOW = LocalDateTime.parse("2026-04-24T01:00:00");

    @BeforeEach
    void setUp() {
        jpaPgInboxRepository.deleteAll();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 진행 중 행 — 정상 증가
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("IN_PROGRESS 행은 attempt 와 updated_at 이 함께 증가한다")
    void incrementAttempt_진행중_행_횟수와_갱신시각_증가() {
        // given
        String orderId = "order-guard-in-progress-" + UUID.randomUUID();
        saveInboxRow(orderId, PgInboxStatus.IN_PROGRESS, 1);

        // when
        int updatedRows = jpaPgInboxRepository.incrementAttempt(orderId, INCREMENT_NOW, PgInboxStatus.IN_PROGRESS);

        // then
        PgInboxEntity after = jpaPgInboxRepository.findByOrderId(orderId).orElseThrow();
        assertThat(updatedRows).isEqualTo(1);
        assertThat(after.getAttempt()).isEqualTo(2);
        assertThat(after.getUpdatedAt()).isEqualTo(INCREMENT_NOW);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 종결 행 — attempt / updated_at 불변
    // ─────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = PgInboxStatus.class, names = {"APPROVED", "FAILED", "QUARANTINED"})
    @DisplayName("종결 상태 행은 incrementAttempt 호출 후에도 attempt 와 updated_at 이 그대로다")
    void incrementAttempt_종결_행_횟수와_갱신시각_불변(PgInboxStatus terminalStatus) {
        // given
        String orderId = "order-guard-terminal-" + UUID.randomUUID();
        saveInboxRow(orderId, terminalStatus, 3);

        // when
        jpaPgInboxRepository.incrementAttempt(orderId, INCREMENT_NOW, PgInboxStatus.IN_PROGRESS);

        // then
        PgInboxEntity after = jpaPgInboxRepository.findByOrderId(orderId).orElseThrow();
        assertThat(after.getAttempt())
                .as("종결 행의 attempt 는 증가하지 않아야 함")
                .isEqualTo(3);
        assertThat(after.getUpdatedAt())
                .as("종결 행의 updated_at(종결 시각)은 밀리지 않아야 함")
                .isEqualTo(FIXED_UPDATED_AT);
    }

    @ParameterizedTest
    @EnumSource(value = PgInboxStatus.class, names = {"APPROVED", "FAILED", "QUARANTINED"})
    @DisplayName("종결 상태 행에는 반영 행 수 0을 반환한다")
    void incrementAttempt_종결_행_반영행수_0(PgInboxStatus terminalStatus) {
        // given
        String orderId = "order-guard-zero-" + UUID.randomUUID();
        saveInboxRow(orderId, terminalStatus, 1);

        // when
        int updatedRows = jpaPgInboxRepository.incrementAttempt(orderId, INCREMENT_NOW, PgInboxStatus.IN_PROGRESS);

        // then
        assertThat(updatedRows)
                .as("종결 행은 가드에 걸려 UPDATE 가 무동작해야 함")
                .isZero();
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    private void saveInboxRow(String orderId, PgInboxStatus status, int attempt) {
        PgInboxEntity entity = PgInboxEntity.builder()
                .orderId(orderId)
                .status(status)
                .amount(AMOUNT)
                .createdAt(FIXED_UPDATED_AT)
                .updatedAt(FIXED_UPDATED_AT)
                .attempt(attempt)
                .build();
        jpaPgInboxRepository.save(entity);
    }
}
