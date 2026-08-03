package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.core.config.ClockConfig;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOutboxEntity;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
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
 * JpaPaymentOutboxRepository — 이미 있으면 넘어가는 삽입 + 확인 조회 단위 테스트.
 * MySQL Testcontainers 기반 — INSERT IGNORE 가 실제로 예외 없이 넘어가는지 검증.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ClockConfig.class)
@DisplayName("JpaPaymentOutboxRepository — 이미 있으면 넘어가는 삽입 + 확인 조회")
class JpaPaymentOutboxRepositoryTest {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("payment-outbox-lock-test")
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
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private JpaPaymentOutboxRepository jpaPaymentOutboxRepository;

    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        jpaPaymentOutboxRepository.deleteAll();
    }

    @Test
    @DisplayName("새_주문_삽입은_반영_행이_1이다")
    void 새_주문_삽입은_반영_행이_1이다() {
        // given
        String orderId = "order-outbox-lock-001";
        LocalDateTime now = LocalDateTime.now(clock);

        // when
        int affected = jpaPaymentOutboxRepository.insertIgnorePending(orderId, now);

        // then
        assertThat(affected).isEqualTo(1);
        Optional<PaymentOutboxEntity> saved = jpaPaymentOutboxRepository.findByOrderId(orderId);
        assertThat(saved).isPresent();
        assertThat(saved.get().getStatus()).isEqualTo(PaymentOutboxStatus.PENDING);
    }

    @Test
    @DisplayName("이미_있는_주문으로_삽입하면_반영_행이_0이고_예외가_없다")
    void 이미_있는_주문으로_삽입하면_반영_행이_0이고_예외가_없다() {
        // given
        String orderId = "order-outbox-lock-002";
        LocalDateTime now = LocalDateTime.now(clock);
        jpaPaymentOutboxRepository.insertIgnorePending(orderId, now);

        // when
        int affected = jpaPaymentOutboxRepository.insertIgnorePending(orderId, now);

        // then — 예외 없이 0 반환, 행은 여전히 1개
        assertThat(affected).isEqualTo(0);
        assertThat(jpaPaymentOutboxRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("잠금_읽기_조회가_기존_행을_반환한다")
    void 잠금_읽기_조회가_기존_행을_반환한다() {
        // given
        String orderId = "order-outbox-lock-003";
        LocalDateTime now = LocalDateTime.now(clock);
        jpaPaymentOutboxRepository.insertIgnorePending(orderId, now);

        // when
        Optional<PaymentOutboxEntity> found = jpaPaymentOutboxRepository.findByOrderIdForUpdate(orderId);

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getOrderId()).isEqualTo(orderId);
    }
}
