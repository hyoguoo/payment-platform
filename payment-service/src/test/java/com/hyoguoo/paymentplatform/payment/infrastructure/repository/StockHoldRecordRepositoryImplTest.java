package com.hyoguoo.paymentplatform.payment.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordSnapshot;
import com.hyoguoo.paymentplatform.payment.core.config.ClockConfig;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.StockHoldRecordStatus;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * StockHoldRecordRepositoryImpl — 재오픈·확정 보존·사이클 식별 단위 테스트.
 * MySQL Testcontainers 기반 — 유일 제약과 조건부 갱신이 실제 DB 위에서 성립하는지 검증.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({StockHoldRecordRepositoryImpl.class, ClockConfig.class})
@DisplayName("StockHoldRecordRepositoryImpl — 재오픈·확정 보존·사이클 식별")
class StockHoldRecordRepositoryImplTest {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("stock-hold-record-test")
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

    private static final String ORDER_ID = "order-shr-001";
    private static final Long PRODUCT_ID = 501L;
    private static final Integer QUANTITY = 3;

    @Autowired
    private StockHoldRecordRepositoryImpl sut;

    @Autowired
    private JpaStockHoldRecordRepository jpaStockHoldRecordRepository;

    @BeforeEach
    void setUp() {
        jpaStockHoldRecordRepository.deleteAll();
    }

    @Test
    @DisplayName("같은 주문·상품으로 여러 번 열어도 행이 하나만 남는다")
    void 같은_주문_상품으로_여러_번_열어도_행이_하나만_남는다() {
        // given & when
        sut.openHold(ORDER_ID, product());
        sut.openHold(ORDER_ID, product());
        sut.openHold(ORDER_ID, product());

        // then
        assertThat(jpaStockHoldRecordRepository.count()).isEqualTo(1);
        Optional<StockHoldRecordSnapshot> snapshot = sut.findSnapshot(ORDER_ID, product());
        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().status()).isEqualTo(StockHoldRecordStatus.NOISE);
    }

    @Test
    @DisplayName("되돌림으로 닫힌 행에 새 차감이 들어오면 잡음으로 다시 열린다")
    void 되돌림으로_닫힌_행에_새_차감이_들어오면_잡음으로_다시_열린다() {
        // given — 첫 사이클을 열고 되돌림으로 닫는다
        String firstCycleToken = sut.openHold(ORDER_ID, product());
        boolean closed = sut.closeAsReverted(ORDER_ID, product(), firstCycleToken);
        assertThat(closed).isTrue();
        assertThat(sut.findSnapshot(ORDER_ID, product()).orElseThrow().status())
                .isEqualTo(StockHoldRecordStatus.REVERTED);

        // when — 같은 조합에 새 차감이 들어온다
        String secondCycleToken = sut.openHold(ORDER_ID, product());

        // then — 잡음으로 재오픈되고 사이클 식별 값이 새로 발급된다
        StockHoldRecordSnapshot snapshot = sut.findSnapshot(ORDER_ID, product()).orElseThrow();
        assertThat(snapshot.status()).isEqualTo(StockHoldRecordStatus.NOISE);
        assertThat(secondCycleToken).isNotEqualTo(firstCycleToken);
        assertThat(snapshot.cycleToken()).isEqualTo(secondCycleToken);
        assertThat(jpaStockHoldRecordRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("확정 행에 새 차감이 들어와도 확정으로 유지된다")
    void 확정_행에_새_차감이_들어와도_확정으로_유지된다() {
        // given — 잡음을 확정으로 바꾼다
        String committedCycleToken = sut.openHold(ORDER_ID, product());
        sut.commitAllByOrderId(ORDER_ID);
        assertThat(sut.findSnapshot(ORDER_ID, product()).orElseThrow().status())
                .isEqualTo(StockHoldRecordStatus.COMMITTED);

        // when — 확정된 조합에 재요청이 들어와도 캐시 차감 호출은 호출부 몫이고,
        // 기록만 보면 openHold 가 다시 불려도 확정 상태를 건드리면 안 된다
        String returnedCycleToken = sut.openHold(ORDER_ID, product());

        // then — 상태와 사이클 식별 값 모두 그대로다
        StockHoldRecordSnapshot snapshot = sut.findSnapshot(ORDER_ID, product()).orElseThrow();
        assertThat(snapshot.status()).isEqualTo(StockHoldRecordStatus.COMMITTED);
        assertThat(snapshot.cycleToken()).isEqualTo(committedCycleToken);
        assertThat(returnedCycleToken).isEqualTo(committedCycleToken);
        assertThat(jpaStockHoldRecordRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("닫기는 자기 사이클 식별 값이 일치할 때만 성공하고, 그사이 새 사이클이 열렸으면 실패한다")
    void 닫기는_자기_사이클_식별_값이_일치할_때만_성공한다() {
        // given — 첫 사이클을 연 뒤, 그 값을 쥐기만 하고 아직 닫지 않은 상태에서
        // 다른 경로(예: 되돌리기 후 재시도)가 같은 행을 다시 열어 새 사이클을 만든다
        String staleCycleToken = sut.openHold(ORDER_ID, product());
        String freshCycleToken = sut.openHold(ORDER_ID, product());
        assertThat(freshCycleToken).isNotEqualTo(staleCycleToken);

        // when — 뒤늦게 옛 사이클 식별 값으로 닫기를 시도한다
        boolean staleClose = sut.closeAsReverted(ORDER_ID, product(), staleCycleToken);

        // then — 반영되지 않고, 새 사이클은 잡음 상태 그대로다
        assertThat(staleClose).isFalse();
        StockHoldRecordSnapshot snapshotAfterStaleClose = sut.findSnapshot(ORDER_ID, product()).orElseThrow();
        assertThat(snapshotAfterStaleClose.status()).isEqualTo(StockHoldRecordStatus.NOISE);
        assertThat(snapshotAfterStaleClose.cycleToken()).isEqualTo(freshCycleToken);

        // when — 지금 사이클 식별 값으로 닫으면 성공한다
        boolean freshClose = sut.closeAsReverted(ORDER_ID, product(), freshCycleToken);

        // then
        assertThat(freshClose).isTrue();
        assertThat(sut.findSnapshot(ORDER_ID, product()).orElseThrow().status())
                .isEqualTo(StockHoldRecordStatus.REVERTED);
    }

    /**
     * 유일 제약 동시 삽입 테스트.
     * @Transactional(NOT_SUPPORTED) — 두 스레드가 각자 독립 TX 로 openHold 를 실행해야
     * 서로의 커밋 여부가 실제 유니크 인덱스 경합에 반영된다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("openHold — 같은 주문·상품 조합 두 스레드 동시 호출: 행은 하나만 생긴다")
    void openHold_같은_조합_두_스레드_동시_호출_행은_하나만_생긴다() throws InterruptedException {
        // given
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicReference<String> firstToken = new AtomicReference<>();
        AtomicReference<String> secondToken = new AtomicReference<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.submit(() -> openHoldAfterGate(startGate, doneLatch, firstToken));
        executor.submit(() -> openHoldAfterGate(startGate, doneLatch, secondToken));

        // when — 두 스레드를 신호로 동시에 푼다
        startGate.countDown();
        doneLatch.await();
        executor.shutdown();

        // then — 행은 하나만 생긴다
        assertThat(firstToken.get()).isNotNull();
        assertThat(secondToken.get()).isNotNull();
        assertThat(jpaStockHoldRecordRepository.count()).isEqualTo(1);

        // cleanup — NOT_SUPPORTED 이므로 @BeforeEach 롤백이 없어 수동 정리
        jpaStockHoldRecordRepository.deleteAll();
    }

    private void openHoldAfterGate(CountDownLatch startGate, CountDownLatch doneLatch,
                                   AtomicReference<String> resultHolder) {
        try {
            startGate.await();
            resultHolder.set(sut.openHold(ORDER_ID, product()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            doneLatch.countDown();
        }
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
