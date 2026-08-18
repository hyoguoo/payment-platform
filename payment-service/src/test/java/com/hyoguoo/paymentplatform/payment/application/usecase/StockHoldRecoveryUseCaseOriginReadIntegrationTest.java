package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.core.config.ClockConfig;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.PaymentEventRepositoryImpl;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.StockHoldRecordRepositoryImpl;
import com.hyoguoo.paymentplatform.payment.mock.FakeStockCachePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MySQLContainer;

/**
 * StockHoldRecoveryUseCase 가 결제 상태 조회에 쓰는 {@link PaymentEventRepository} 빈이 쓰기
 * 경로({@code PaymentTransactionCoordinator}/{@code PaymentConfirmResultUseCase})와 동일한지를
 * 구조로 고정한다.
 *
 * <p>지금은 이 앱에 {@link PaymentEventRepository} 구현체가 하나뿐이라, 느슨하게(예: 별도 Fake나
 * 항상 같은 값을 돌려주는 스텁으로) 검증하면 항상 통과하는 테스트가 된다. 이 테스트는 그 대신
 * Spring 컨텍스트가 실제로 주입한 빈 인스턴스 자체를 비교한다 — 후행 토픽이 회수 판정에만
 * {@code @Qualifier}로 읽기 복제본 빈을 따로 연결하면(라우팅 데이터소스를 끼우는 통상적인 방식)
 * 이 테스트가 그 시점에 깨진다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        PaymentEventRepositoryImpl.class,
        StockHoldRecordRepositoryImpl.class,
        ClockConfig.class,
        StockHoldRecoveryUseCase.class,
        StockHoldRecoveryUseCaseOriginReadIntegrationTest.TestConfig.class
})
@DisplayName("StockHoldRecoveryUseCase — 결제 상태 조회 빈 고정")
class StockHoldRecoveryUseCaseOriginReadIntegrationTest {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("stock-hold-recovery-origin-test")
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

    /**
     * 쓰기 경로가 참조하는 것과 같은, 한정자 없는 기본 {@link PaymentEventRepository} 빈 —
     * {@code PaymentCommandUseCase}/{@code PaymentConfirmResultUseCase}가 생성자에서 그대로
     * 받는 것과 동일한 주입 방식이다.
     */
    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private StockHoldRecoveryUseCase stockHoldRecoveryUseCase;

    @Test
    @DisplayName("회수 판정이 쓰기 경로와 같은 PaymentEventRepository 빈 인스턴스를 그대로 쓴다")
    void 회수_판정이_쓰기_경로와_동일한_빈을_쓴다() {
        Object injected = ReflectionTestUtils.getField(stockHoldRecoveryUseCase, "paymentEventRepository");

        assertThat(injected).isSameAs(paymentEventRepository);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        StockCachePort stockCachePort() {
            return new FakeStockCachePort();
        }
    }
}
