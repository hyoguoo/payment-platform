package com.hyoguoo.paymentplatform.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentOutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * 발행 재시도 간격 통합 테스트.
 *
 * <p>Task 6(대기 상태 전용 간격 기록 도메인 메서드)과 Task 7(워커의 조건부 갱신)이 함께 만드는
 * "발행 실패 → 간격 기록 → 그 간격이 지날 때까지 재픽업 제외"의 전 구간을, 기존 대기 배치
 * 조회 쿼리({@link JpaPaymentOutboxRepository#findPendingBatch})를 그대로 통해 검증한다.
 * 이 쿼리를 바꾸지 않고 재사용하기로 한 설계 결정의 실증이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@Import(OutboxPublishRetryIntervalIntegrationTest.TestClockConfig.class)
@DisplayName("발행 재시도 간격 통합 테스트")
class OutboxPublishRetryIntervalIntegrationTest {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("payment-outbox-retry-interval-test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
                    .withReuse(true);

    static {
        // @Testcontainers/@Container 를 사용하지 않고 수동 start.
        // @Container 로 관리하면 JUnit5 extension 이 테스트 클래스 완료 후 stop() 을 명시 호출하여
        // withReuse(true) 설정에도 불구하고 컨테이너가 종료된다.
        MYSQL_CONTAINER.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // defer-datasource-initialization 비활성화 — Flyway 활성화 시 circular depends-on 방지
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("scheduler.enabled", () -> "false");
        // Redis — 이 테스트 범위 밖이므로 컨텍스트 로드용 dummy 설정
        registry.add("payment.cache.stock-redis.host", () -> "localhost");
        registry.add("payment.cache.stock-redis.port", () -> "6380");
    }

    /**
     * 테스트용 가변 Clock 빈 — 발행 간격이 지나기 전/후를 결정적으로 재현한다.
     */
    @TestConfiguration
    static class TestClockConfig {

        @Bean
        @Primary
        public TestClock clock() {
            return new TestClock();
        }
    }

    static class TestClock extends Clock {

        private final AtomicReference<Instant> fixedInstant = new AtomicReference<>(Instant.now());

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return fixedInstant.get();
        }

        void setFixedInstant(Instant instant) {
            this.fixedInstant.set(instant);
        }
    }

    @Autowired
    private PaymentOutboxUseCase paymentOutboxUseCase;

    @Autowired
    private JpaPaymentOutboxRepository jpaPaymentOutboxRepository;

    @Autowired
    private TestClock testClock;

    @BeforeEach
    void setUp() {
        jpaPaymentOutboxRepository.deleteAllInBatch();
    }

    @AfterEach
    void tearDown() {
        jpaPaymentOutboxRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("발행 실패 기록 후 간격이 지날 때까지 대기 배치 조회에서 제외되고, 지나면 다시 돌아온다")
    void 발행_실패_기록_후_간격이_지날_때까지_대기_배치_조회에서_제외되고_지나면_다시_돌아온다() {
        // given — 발행 트랜잭션 롤백 직후와 같은 대기 상태(재시도 0회)
        String orderId = "order-outbox-retry-interval-" + UUID.randomUUID();
        Instant t0 = Instant.now();
        testClock.setFixedInstant(t0);
        paymentOutboxUseCase.createPendingRecord(orderId);

        // when — 발행 실패 간격을 기록한다 (FIXED 정책, application.yml 기본 5000ms)
        paymentOutboxUseCase.recordPublishFailureDelay(orderId);

        // then (1) — 다음 시도 시각이 채워진다
        PaymentOutbox recorded = paymentOutboxUseCase.findByOrderId(orderId).orElseThrow();
        Instant expectedNextRetryAt = t0.plus(Duration.ofSeconds(5));
        assertThat(recorded.getNextRetryAt()).isEqualTo(expectedNextRetryAt);
        assertThat(recorded.getRetryCount()).isEqualTo(1);

        // then (2) — 그 시각 이전에는 대기 배치 조회가 이 행을 돌려주지 않는다
        List<PaymentOutbox> beforeInterval = paymentOutboxUseCase.findPendingBatch(10);
        assertThat(beforeInterval).extracting(PaymentOutbox::getOrderId).doesNotContain(orderId);

        // then (3) — 시각이 지나면 다시 돌려준다
        testClock.setFixedInstant(expectedNextRetryAt.plusSeconds(1));
        List<PaymentOutbox> afterInterval = paymentOutboxUseCase.findPendingBatch(10);
        assertThat(afterInterval).extracting(PaymentOutbox::getOrderId).contains(orderId);
    }
}
