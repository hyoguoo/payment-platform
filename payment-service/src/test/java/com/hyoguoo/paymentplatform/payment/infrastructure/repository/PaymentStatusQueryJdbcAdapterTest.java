package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentStatusSnapshot;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * PaymentStatusQueryJdbcAdapter 통합 테스트.
 *
 * <p>Spring 컨텍스트를 띄우지 않는다 — 어댑터가 {@link JdbcTemplate} 하나만 받으므로
 * 컨테이너 접속 정보로 직접 만들어 생성자에 넘긴다. 어댑터가 JPA 를 쓰지 않아
 * {@code @DataJpaTest} 는 맞지 않고, 전체 부팅은 이 검증에 필요 없다.
 */
@Tag("integration")
@DisplayName("PaymentStatusQueryJdbcAdapter 통합 테스트")
class PaymentStatusQueryJdbcAdapterTest {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("payment-status-query-adapter-test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
                    // raw-JDBC 경로 UTC round-trip 강제 — Task 3 데이터소스 설정과 같은 파라미터.
                    .withUrlParam("connectionTimeZone", "UTC")
                    .withUrlParam("forceConnectionTimeZoneToSession", "true")
                    .withReuse(true);

    static {
        MYSQL_CONTAINER.start();
        Flyway.configure()
                .dataSource(MYSQL_CONTAINER.getJdbcUrl(), MYSQL_CONTAINER.getUsername(), MYSQL_CONTAINER.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private JdbcTemplate jdbcTemplate;
    private PaymentStatusQueryJdbcAdapter adapter;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(MYSQL_CONTAINER.getJdbcUrl());
        dataSource.setUsername(MYSQL_CONTAINER.getUsername());
        dataSource.setPassword(MYSQL_CONTAINER.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        adapter = new PaymentStatusQueryJdbcAdapter(jdbcTemplate);

        jdbcTemplate.update("DELETE FROM payment_outbox");
        jdbcTemplate.update("DELETE FROM payment_event");
    }

    @Test
    @DisplayName("발행 대기 행이면 PENDING")
    void findActiveOutboxStatus_발행대기_행이면_PENDING() {
        String orderId = newOrderId();
        insertOutbox(orderId, PaymentOutboxStatus.PENDING);

        Optional<PaymentOutboxStatus> result = adapter.findActiveOutboxStatus(orderId);

        assertThat(result).contains(PaymentOutboxStatus.PENDING);
    }

    @Test
    @DisplayName("발행 중 행이면 IN_FLIGHT")
    void findActiveOutboxStatus_발행중_행이면_IN_FLIGHT() {
        String orderId = newOrderId();
        insertOutbox(orderId, PaymentOutboxStatus.IN_FLIGHT);

        Optional<PaymentOutboxStatus> result = adapter.findActiveOutboxStatus(orderId);

        assertThat(result).contains(PaymentOutboxStatus.IN_FLIGHT);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentOutboxStatus.class, names = {"PENDING", "IN_FLIGHT"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("종결된 발행 기록이면 빈 값")
    void findActiveOutboxStatus_종결된_발행기록이면_빈값(PaymentOutboxStatus terminalStatus) {
        String orderId = newOrderId();
        insertOutbox(orderId, terminalStatus);

        Optional<PaymentOutboxStatus> result = adapter.findActiveOutboxStatus(orderId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("행이 없으면 빈 값")
    void findActiveOutboxStatus_행이_없으면_빈값() {
        Optional<PaymentOutboxStatus> result = adapter.findActiveOutboxStatus(newOrderId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("주문번호로 상태와 승인시각을 읽는다")
    void findStatusSnapshot_주문번호로_상태와_승인시각을_읽는다() {
        String orderId = newOrderId();
        Instant approvedAt = Instant.parse("2026-08-25T03:15:30.123456Z");
        insertEvent(orderId, PaymentEventStatus.DONE, approvedAt);

        Optional<PaymentStatusSnapshot> result = adapter.findStatusSnapshot(orderId);

        assertThat(result).isPresent();
        PaymentStatusSnapshot snapshot = result.get();
        assertThat(snapshot.orderId()).isEqualTo(orderId);
        assertThat(snapshot.status()).isEqualTo(PaymentEventStatus.DONE);
        assertThat(snapshot.approvedAt())
                .as("connectionTimeZone=UTC 왕복 — 저장한 절대시점과 동치")
                .isEqualTo(approvedAt);
    }

    @Test
    @DisplayName("행이 없으면 빈 값")
    void findStatusSnapshot_행이_없으면_빈값() {
        Optional<PaymentStatusSnapshot> result = adapter.findStatusSnapshot(newOrderId());

        assertThat(result).isEmpty();
    }

    // ────────────────────────────────────────────────────────────
    // 헬퍼
    // ────────────────────────────────────────────────────────────

    private String newOrderId() {
        return "order-" + UUID.randomUUID();
    }

    private void insertOutbox(String orderId, PaymentOutboxStatus status) {
        jdbcTemplate.update(
                "INSERT INTO payment_outbox (order_id, status, retry_count) VALUES (?, ?, 0)",
                orderId, status.name());
    }

    private void insertEvent(String orderId, PaymentEventStatus status, Instant approvedAt) {
        jdbcTemplate.update(
                "INSERT INTO payment_event "
                        + "(buyer_id, seller_id, order_name, order_id, gateway_type, status, approved_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                1L, 1L, "테스트 주문", orderId, "TOSS", status.name(),
                approvedAt != null ? Timestamp.from(approvedAt) : null);
    }
}
