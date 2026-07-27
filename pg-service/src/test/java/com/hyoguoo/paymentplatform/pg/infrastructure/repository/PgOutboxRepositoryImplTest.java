package com.hyoguoo.paymentplatform.pg.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.infrastructure.entity.PgOutboxEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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

/**
 * PgOutboxRepositoryImpl.findConfirmAttemptRows 관리자 이력 조회 통합 테스트.
 *
 * <p>{@link PgInboxRepositoryImplTest} 의 {@code @DataJpaTest} + 정적 Testcontainers MySQL 패턴을
 * 그대로 따른다 — V6 인덱스({@code key, topic})를 실제 태우는지, 결과 발행 토픽 행이 새지 않는지는
 * 실제 DB 로만 검증할 수 있다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PgOutboxRepositoryImpl.class)
@DisplayName("PgOutboxRepositoryImpl.findConfirmAttemptRows — 주문번호별 이력 행 조회")
class PgOutboxRepositoryImplTest {

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
    private PgOutboxRepositoryImpl sut;

    @Autowired
    private JpaPgOutboxRepository jpaRepository;

    private static final LocalDateTime BASE_TIME = LocalDateTime.parse("2026-04-24T00:00:00");

    @BeforeEach
    void setUp() {
        jpaRepository.deleteAll();
    }

    @Test
    @DisplayName("주문번호가 일치하는 행만 반환한다 — 다른 주문의 행은 섞이지 않는다")
    void findConfirmAttemptRows_주문번호_일치_행만_반환() {
        // given
        String orderId = "order-attempt-" + UUID.randomUUID();
        String otherOrderId = "order-attempt-other-" + UUID.randomUUID();
        saveOutboxRow(orderId, PgTopics.COMMANDS_CONFIRM, BASE_TIME);
        saveOutboxRow(otherOrderId, PgTopics.COMMANDS_CONFIRM, BASE_TIME);

        // when
        List<PgOutbox> rows = sut.findConfirmAttemptRows(orderId);

        // then
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getKey()).isEqualTo(orderId);
    }

    @Test
    @DisplayName("확정 명령과 소진 토픽 행만 반환한다 — 결과 발행 토픽 행은 섞이지 않는다")
    void findConfirmAttemptRows_확정명령과_소진_토픽만_반환() {
        // given
        String orderId = "order-attempt-" + UUID.randomUUID();
        saveOutboxRow(orderId, PgTopics.COMMANDS_CONFIRM, BASE_TIME);
        saveOutboxRow(orderId, PgTopics.COMMANDS_CONFIRM_DLQ, BASE_TIME.plusMinutes(1));
        // 재발행 서비스(PgTerminalReemitService)가 만든 결과 발행 행 — 시도로 오독되면 안 된다
        saveOutboxRow(orderId, PgTopics.EVENTS_CONFIRMED, BASE_TIME.plusMinutes(2));

        // when
        List<PgOutbox> rows = sut.findConfirmAttemptRows(orderId);

        // then
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(PgOutbox::getTopic)
                .containsExactly(PgTopics.COMMANDS_CONFIRM, PgTopics.COMMANDS_CONFIRM_DLQ);
    }

    @Test
    @DisplayName("생성 시각 오름차순으로 반환한다 — 타임라인 순서 고정")
    void findConfirmAttemptRows_생성시각_오름차순() {
        // given
        String orderId = "order-attempt-" + UUID.randomUUID();
        saveOutboxRow(orderId, PgTopics.COMMANDS_CONFIRM_DLQ, BASE_TIME.plusMinutes(10));
        saveOutboxRow(orderId, PgTopics.COMMANDS_CONFIRM, BASE_TIME);
        saveOutboxRow(orderId, PgTopics.COMMANDS_CONFIRM, BASE_TIME.plusMinutes(5));

        // when
        List<PgOutbox> rows = sut.findConfirmAttemptRows(orderId);

        // then
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(PgOutbox::getCreatedAt)
                .isSorted();
    }

    @Test
    @DisplayName("해당 주문의 행이 없으면 빈 목록을 반환한다")
    void findConfirmAttemptRows_해당_주문_행_없으면_빈_목록() {
        // given
        String orderId = "order-attempt-none-" + UUID.randomUUID();

        // when
        List<PgOutbox> rows = sut.findConfirmAttemptRows(orderId);

        // then
        assertThat(rows).isEmpty();
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────────────────

    private void saveOutboxRow(String orderId, String topic, LocalDateTime createdAt) {
        PgOutboxEntity entity = PgOutboxEntity.builder()
                .topic(topic)
                .key(orderId)
                .payload("{}")
                .headersJson(null)
                .availableAt(createdAt)
                .processedAt(null)
                .attempt(0)
                .createdAt(createdAt)
                .build();
        jpaRepository.save(entity);
    }
}
