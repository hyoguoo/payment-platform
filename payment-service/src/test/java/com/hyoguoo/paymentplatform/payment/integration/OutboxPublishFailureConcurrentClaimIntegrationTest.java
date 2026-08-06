package com.hyoguoo.paymentplatform.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentOutboxRepository;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

/**
 * 발행 실패 간격 기록과 다른 워커의 선점(claimToInFlight)이 실제 시점에 겹칠 때, 기록이 선점을
 * 되돌리지 않는지 검증한다.
 *
 * <p>기록은 상태·횟수를 함께 조건으로 거는 조건부 갱신이라, 선점이 먼저 커밋되면 기록의
 * 갱신문은 WHERE 절이 어긋나 0건으로 끝난다 — 선점된 행의 상태를 되돌릴 수단 자체가 없다.
 * "읽어서 고친 뒤 저장" 방식(조건절 없는 전체 덮어쓰기)으로 회귀하면 이 불변식이 race 안에서만
 * 간헐적으로 깨지므로 단일 스레드 테스트로는 잡히지 않는다 — {@code CountDownLatch}로 두 작업을
 * 같은 시점에 풀고 반복해 재현한다.
 *
 * <p>두 갱신문 모두 실행 시 트랜잭션이 필요하다({@code @Modifying} 쿼리). 운영 코드에서는
 * 각각의 서비스 메서드({@code PaymentOutboxUseCase#recordPublishFailureDelay} /
 * {@code OutboxRelayService#relay})가 트랜잭션 경계를 연다. 이 테스트는 그 두 서비스를 함께
 * 태우지 않고 조건부 갱신 자체의 경합만 분리해서 보므로, 각 스레드에서 {@link TransactionTemplate}
 * 로 독립된 트랜잭션을 직접 연다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@DisplayName("발행 실패 간격 기록 vs 선점 동시 경합 통합 테스트")
class OutboxPublishFailureConcurrentClaimIntegrationTest {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("payment-outbox-retry-claim-race-test")
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

    @Autowired
    private PaymentOutboxUseCase paymentOutboxUseCase;

    @Autowired
    private PaymentOutboxRepository paymentOutboxRepository;

    @Autowired
    private JpaPaymentOutboxRepository jpaPaymentOutboxRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        jpaPaymentOutboxRepository.deleteAllInBatch();
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        jpaPaymentOutboxRepository.deleteAllInBatch();
    }

    @RepeatedTest(50)
    @DisplayName("발행 실패 간격 조건부 갱신과 다른 워커의 선점을 동시에 실행해도, 기록이 선점을 되돌리지 않는다")
    void 발행_실패_간격_기록과_선점이_겹쳐도_기록이_선점을_되돌리지_않는다() throws Exception {
        // given — 발행 실패로 트랜잭션이 롤백된 직후와 같은 대기 상태(재시도 0회).
        // 반복마다 새 주문 번호를 써야 앞선 반복이 남긴 행 때문에 경합이 무력화되지 않는다.
        String orderId = "order-outbox-claim-race-" + UUID.randomUUID();
        paymentOutboxUseCase.createPendingRecord(orderId);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean recorded = new AtomicBoolean();
        AtomicBoolean claimed = new AtomicBoolean();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // when — 읽은 시점의 횟수(0)를 조건으로 건 조건부 갱신과, 다른 워커의 선점을 각자
        // 독립된 트랜잭션으로 같은 시점에 겹쳐 실행한다. 둘 다 상태를 조건으로 건 단일
        // UPDATE 라 실제 DB 행 잠금 경합이 일어난다.
        executor.submit(() -> {
            ready.countDown();
            awaitQuietly(start);
            Boolean result = transactionTemplate.execute(status -> paymentOutboxRepository.recordRetryDelay(
                    orderId, 0, 1, Instant.now().plusSeconds(5)));
            recorded.set(Boolean.TRUE.equals(result));
        });
        executor.submit(() -> {
            ready.countDown();
            awaitQuietly(start);
            Boolean result = transactionTemplate.execute(status ->
                    paymentOutboxRepository.claimToInFlight(orderId, Instant.now()));
            claimed.set(Boolean.TRUE.equals(result));
        });

        ready.await();
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // then — 정확히 한 쪽만 반영된다. 기록의 조건(status=PENDING)과 선점의 조건이 같은
        // 상태를 겨냥하므로, 먼저 커밋된 쪽이 다른 쪽의 조건을 어긋나게 만든다 — 기록이 먼저
        // 커밋되면 nextRetryAt 이 미래로 채워져 선점의 시각 조건이 막히고, 선점이 먼저
        // 커밋되면 상태가 IN_FLIGHT 로 바뀌어 기록의 상태 조건이 막힌다.
        boolean exactlyOneWon = recorded.get() != claimed.get();
        assertThat(exactlyOneWon)
                .as("기록과 선점 중 정확히 하나만 반영돼야 한다(둘 다 상태=PENDING 을 조건으로 건다)")
                .isTrue();

        // then — 선점이 이겼다면, 최종 상태는 반드시 IN_FLIGHT 로 남는다. 기록은 상태·횟수
        // 조건부 갱신이라 선점된 행을 대기 상태로 되돌릴 수 없다.
        PaymentOutbox finalOutbox = paymentOutboxRepository.findByOrderId(orderId).orElseThrow();
        if (claimed.get()) {
            assertThat(finalOutbox.getStatus())
                    .as("기록이 선점을 되돌리지 않는다 — 선점 성공 후 최종 상태는 IN_FLIGHT 로 남는다")
                    .isEqualTo(PaymentOutboxStatus.IN_FLIGHT);
            assertThat(finalOutbox.getRetryCount())
                    .as("선점이 이기면 기록은 전혀 반영되지 않아 재시도 횟수가 그대로 0이다")
                    .isZero();
        } else {
            assertThat(finalOutbox.getStatus())
                    .as("기록이 이기면 상태는 대기 그대로다 — 기록은 상태를 바꾸지 않는다")
                    .isEqualTo(PaymentOutboxStatus.PENDING);
            assertThat(finalOutbox.getRetryCount()).isEqualTo(1);
        }
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기 중 인터럽트", e);
        }
    }
}
