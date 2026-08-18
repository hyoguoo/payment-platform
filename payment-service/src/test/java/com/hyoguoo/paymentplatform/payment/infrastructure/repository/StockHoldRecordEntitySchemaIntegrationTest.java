package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.core.config.ClockConfig;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * StockHoldRecordEntity — 주문번호·상품번호 유일 제약이 스키마 자동 생성 환경에서도 선다.
 *
 * <p>{@code StockHoldRecordRepositoryImplTest}·{@code StockGateConcurrentRetryIntegrationTest}는
 * Flyway 를 켜고 {@code ddl-auto: validate} 로 검증해 이 제약을 실제 마이그레이션 위에서만 확인한다.
 * 이 클래스는 반대로 {@code BaseIntegrationTest} 계열이 쓰는 조합 — {@code test} 프로파일 기본값인
 * {@code ddl-auto: create-drop} + Flyway 비활성 — 을 그대로 두어, Hibernate 가 엔티티 애너테이션만으로
 * 만든 스키마 위에서도 제약이 서는지 검증한다. datasource 만 테스트 컨테이너로 바꾸고 ddl-auto·flyway
 * 는 오버라이드하지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StockHoldRecordRepositoryImpl.class, ClockConfig.class})
@ActiveProfiles("test")
@DisplayName("StockHoldRecordEntity — 스키마 자동 생성 환경에서도 유일 제약이 선다")
class StockHoldRecordEntitySchemaIntegrationTest {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("stock-hold-record-schema-test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
                    .withReuse(true);

    static {
        MYSQL_CONTAINER.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        // ddl-auto·flyway 는 일부러 오버라이드하지 않는다 — test 프로파일 기본값
        // (create-drop + Flyway 비활성)을 그대로 써서 엔티티 애너테이션만으로 제약이 서는지 검증한다.
    }

    private static final String ORDER_ID = "order-shr-schema-001";
    private static final Long PRODUCT_ID = 701L;
    private static final Integer QUANTITY = 4;

    @Autowired
    private StockHoldRecordRepositoryImpl sut;

    @Autowired
    private JpaStockHoldRecordRepository jpaStockHoldRecordRepository;

    @BeforeEach
    void setUp() {
        jpaStockHoldRecordRepository.deleteAll();
    }

    @Test
    @DisplayName("같은 주문·상품 조합을 두 번 기록해도 행이 하나만 남는다")
    void 같은_조합_두_번_기록해도_행이_하나만_남는다() {
        // given & when — 같은 조합으로 두 번 연다(재시도 재현)
        sut.openHold(ORDER_ID, product());
        sut.openHold(ORDER_ID, product());

        // then — 제약이 서 있으면 두 번째는 삽입이 무시되고 재오픈으로 흡수돼 행이 하나만 남는다.
        // 제약이 없으면 두 번째 호출이 새 행을 하나 더 만들어 count() 가 2가 된다.
        assertThat(jpaStockHoldRecordRepository.count())
                .as("유일 제약이 스키마 자동 생성 환경에서도 서서 재진입이 중복 삽입이 아니라 재오픈이어야 한다")
                .isEqualTo(1);
    }

    private PaymentOrder product() {
        return PaymentOrder.allArgsBuilder()
                .orderId(ORDER_ID)
                .productId(PRODUCT_ID)
                .quantity(QUANTITY)
                .totalAmount(BigDecimal.valueOf(1_000))
                .allArgsBuild();
    }
}
