package com.hyoguoo.paymentplatform.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentConfirmPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockDecrementAtomicResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordCandidate;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentFailureUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator.StockDecrementResult;
import com.hyoguoo.paymentplatform.payment.application.usecase.StockHoldRecoveryUseCase;
import com.hyoguoo.paymentplatform.payment.application.util.StockHoldReverter;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentQuarantineMetrics;
import com.hyoguoo.paymentplatform.payment.core.config.ClockConfig;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.StockHoldRecordStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.cache.StockCacheRedisAdapter;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentOrderRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.PaymentEventRepositoryImpl;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.StockHoldRecordRepositoryImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 강제 종료가 상품 반복의 여러 지점에서 일어나도 안전한 방향(과매도 없음)으로 수렴하는지,
 * 그리고 게이트 값이 상품 DB 값과의 차이로 진행 중 선차감을 정확히 설명하는지 검증한다.
 *
 * <p>다섯 시나리오는 설계 문서 "되돌리는 중 서버가 죽으면" 절이 서술하는 수렴 체인을 그대로
 * 태운다 — 강제 종료를 재현할 별도 프로세스 킬 훅은 없다. 죽는 지점 이후의 호출을 그냥 하지
 * 않는 것만으로 재현되므로, 훅이 필요한 지점은 되돌리기·닫기 사이 하나뿐이고 그건 16b 가 이미
 * 신설한 {@link StockHoldReverter#beforeClose}를 재사용한다. 이 클래스는 새 프로덕션 코드를
 * 만들지 않는다.
 *
 * <p>주문번호·상품번호 유일 제약은 Flyway 마이그레이션에만 있어({@code StockGateConcurrentRetryIntegrationTest}
 * 와 같은 이유로) Flyway 를 켜고 {@code ddl-auto: validate} 로 실제 스키마 위에서 검증한다.
 * {@code @DataJpaTest} 기본 트랜잭션(테스트당 단일 커넥션·롤백)을 그대로 쓴다 — 이 클래스의
 * 시나리오는 전부 단일 스레드라 16b 의 닫기 경합 테스트와 달리 스레드 간 락 경합이 없다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentEventRepositoryImpl.class, StockHoldRecordRepositoryImpl.class, ClockConfig.class})
@Testcontainers
@DisplayName("재고 게이트 강제 종료 수렴 체인과 정합 통합 테스트")
class StockGateConvergenceIntegrationTest {

    private static final int INITIAL_STOCK = 1_000;
    private static final Long BUYER_ID = 1L;
    private static final Long SELLER_ID = 2L;

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("stock-gate-convergence-test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
                    .withReuse(true);

    @Container
    static final GenericContainer<?> REDIS_CONTAINER = new GenericContainer<>("redis:7.2-alpine")
            .withCommand("redis-server", "--appendonly", "yes")
            .withExposedPorts(6379);

    static {
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
    private StockHoldRecordRepositoryImpl stockHoldRecordRepository;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private JpaPaymentEventRepository jpaPaymentEventRepository;

    @Autowired
    private JpaPaymentOrderRepository jpaPaymentOrderRepository;

    @Autowired
    private Clock clock;

    private StockCacheRedisAdapter stockCachePort;
    private StringRedisTemplate redisTemplate;
    private LettuceConnectionFactory connectionFactory;
    private SimpleMeterRegistry meterRegistry;
    private StockHoldReverter stockHoldReverter;
    private PaymentCommandUseCase paymentCommandUseCase;
    private PaymentFailureUseCase paymentFailureUseCase;
    private StockHoldRecoveryUseCase stockHoldRecoveryUseCase;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                REDIS_CONTAINER.getHost(),
                REDIS_CONTAINER.getMappedPort(6379)
        );
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        stockCachePort = new StockCacheRedisAdapter(redisTemplate, 30);

        meterRegistry = new SimpleMeterRegistry();
        stockHoldReverter = new StockHoldReverter(meterRegistry);
        paymentCommandUseCase = new PaymentCommandUseCase(
                paymentEventRepository, clock, new PaymentQuarantineMetrics(meterRegistry));
        paymentFailureUseCase = new PaymentFailureUseCase(paymentCommandUseCase);
        stockHoldRecoveryUseCase = new StockHoldRecoveryUseCase(
                stockHoldRecordRepository, paymentEventRepository, stockCachePort, stockHoldReverter);
    }

    @AfterEach
    void tearDown() {
        RedisConnectionFactory factory = redisTemplate.getConnectionFactory();
        if (factory == null) {
            throw new IllegalStateException("RedisConnectionFactory must not be null");
        }
        RedisConnection connection = factory.getConnection();
        if (connection == null) {
            throw new IllegalStateException("RedisConnection must not be null");
        }
        connection.serverCommands().flushAll();
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("상품 반복 도중 강제 종료 — 주문 상태가 갈라지지 않고, 만료 후 회수가 남은 기록을 정리한다")
    void 상품_반복_도중_강제_종료_만료_후_회수가_남은_기록을_정리한다() {
        String orderId = "order-loop-crash-701";
        Long productWithHold = 701L;
        Long productNeverReached = 702L;
        int quantityWithHold = 3;
        redisTemplate.opsForValue().set(stockKey(productWithHold), String.valueOf(INITIAL_STOCK));
        redisTemplate.opsForValue().set(stockKey(productNeverReached), String.valueOf(INITIAL_STOCK));

        savePaymentEventWithOrders(orderId, Map.of(productWithHold, quantityWithHold, productNeverReached, 2));
        PaymentEvent readyEvent = loadEvent(orderId);
        PaymentOrder orderWithHold = findOrder(readyEvent, productWithHold);
        PaymentOrder orderNeverReached = findOrder(readyEvent, productNeverReached);

        // when — 상품 반복이 첫 상품만 처리한 채 죽는다. 두 번째 상품은 openHold 조차 불리지 않는다.
        stockHoldRecordRepository.openHold(orderId, orderWithHold);
        StockDecrementAtomicResult decrementResult = stockCachePort.decrementAtomic(orderId, orderWithHold);
        assertThat(decrementResult).isEqualTo(StockDecrementAtomicResult.OK);

        // then — 도메인 execute() 가 상품 반복 안에서 호출되지 않으므로 어디서 죽든 시작 전
        // 상태를 유지한다. 부분 전이였다면 뒤이은 expire() 가 EXECUTING 상품에서 예외를 던졌을 것이다.
        PaymentEvent crashedEvent = loadEvent(orderId);
        assertThat(crashedEvent.getStatus()).isEqualTo(PaymentEventStatus.READY);
        assertThat(crashedEvent.getPaymentOrderList())
                .as("상품 반복 도중 죽어도 주문 상태가 상품별로 갈라지지 않는다")
                .extracting(PaymentOrder::getStatus)
                .containsOnly(PaymentOrderStatus.NOT_STARTED);

        // when — 만료 배치가 결제를 종결시킨다.
        PaymentEvent expired = paymentCommandUseCase.expirePayment(crashedEvent);
        assertThat(expired.getStatus()).isEqualTo(PaymentEventStatus.EXPIRED);
        assertThat(expired.getPaymentOrderList())
                .extracting(PaymentOrder::getStatus)
                .containsOnly(PaymentOrderStatus.EXPIRED);

        // when — 회수 작업이 남은 선차감 기록(첫 상품)을 정리한다.
        int recoveredCount = stockHoldRecoveryUseCase.recover(10);

        // then
        assertThat(recoveredCount).isEqualTo(1);
        assertThat(currentStock(productWithHold)).isEqualTo(INITIAL_STOCK);
        assertThat(currentStock(productNeverReached))
                .as("반복이 닿지 못한 상품은 애초에 차감된 적이 없어 그대로다")
                .isEqualTo(INITIAL_STOCK);
        assertThat(stockHoldRecordRepository.findSnapshot(orderId, orderWithHold).orElseThrow().status())
                .isEqualTo(StockHoldRecordStatus.REVERTED);
        assertThat(stockHoldRecordRepository.findSnapshot(orderId, orderNeverReached))
                .as("반복이 닿지 못한 상품은 기록 자체가 없다")
                .isEmpty();
    }

    @Test
    @DisplayName("되돌리는 도중 강제 종료 — 회수가 나머지를 되돌리고, 이미 되돌린 상품은 건드리지 않는다(이중 복원 없음)")
    void 되돌리는_도중_강제_종료되어도_회수가_나머지를_되돌린다() {
        String orderId = "order-revert-crash-703";
        Long revertedBeforeCrash = 703L;
        Long stillDecremented = 704L;
        int quantityReverted = 4;
        int quantityStillDecremented = 3;
        redisTemplate.opsForValue().set(stockKey(revertedBeforeCrash), String.valueOf(INITIAL_STOCK));
        redisTemplate.opsForValue().set(stockKey(stillDecremented), String.valueOf(INITIAL_STOCK));

        savePaymentEventWithOrders(
                orderId, Map.of(revertedBeforeCrash, quantityReverted, stillDecremented, quantityStillDecremented));
        PaymentEvent readyEvent = loadEvent(orderId);
        PaymentOrder orderReverted = findOrder(readyEvent, revertedBeforeCrash);
        PaymentOrder orderStillDecremented = findOrder(readyEvent, stillDecremented);

        // given — 두 상품 모두 직접 차감된 뒤(세 번째 상품 부족을 가정) 되돌리는 도중 죽는다:
        // 첫 상품만 되돌리고 두 번째 상품은 되돌리지 못한다.
        stockHoldRecordRepository.openHold(orderId, orderReverted);
        stockCachePort.decrementAtomic(orderId, orderReverted);
        stockHoldRecordRepository.openHold(orderId, orderStillDecremented);
        stockCachePort.decrementAtomic(orderId, orderStillDecremented);

        stockCachePort.rejectCompensate(orderId, orderReverted);
        assertThat(currentStock(revertedBeforeCrash))
                .as("사전조건 — 죽기 전에 되돌린 상품은 이미 원래 값으로 복원돼야 한다")
                .isEqualTo(INITIAL_STOCK);
        assertThat(currentStock(stillDecremented)).isEqualTo(INITIAL_STOCK - quantityStillDecremented);

        // when — 결제가 재고 부족으로 실패 종결된다. 거절 전용은 기록을 건드리지 않으므로
        // 두 상품 모두 선차감 기록은 여전히 잡음(NOISE) 그대로다.
        PaymentEvent failed = paymentFailureUseCase.handleStockFailure(readyEvent, "재고 부족으로 인한 결제 실패");
        assertThat(failed.getStatus()).isEqualTo(PaymentEventStatus.FAILED);

        // when — 회수가 남은 잡음 기록을 모두 판정한다.
        int recoveredCount = stockHoldRecoveryUseCase.recover(10);

        // then — 이미 되돌린 상품은 흔적이 없어 건드리지 않고 기록만 닫힌다(이중 복원 없음).
        // 아직 되돌리지 못한 상품만 이번에 실제로 복원된다.
        assertThat(recoveredCount).isEqualTo(2);
        assertThat(currentStock(revertedBeforeCrash))
                .as("이미 되돌린 상품을 회수가 다시 건드리지 않는다")
                .isEqualTo(INITIAL_STOCK);
        assertThat(currentStock(stillDecremented)).isEqualTo(INITIAL_STOCK);
        assertThat(stockHoldRecordRepository.findSnapshot(orderId, orderReverted).orElseThrow().status())
                .isEqualTo(StockHoldRecordStatus.REVERTED);
        assertThat(stockHoldRecordRepository.findSnapshot(orderId, orderStillDecremented).orElseThrow().status())
                .isEqualTo(StockHoldRecordStatus.REVERTED);
    }

    @Test
    @DisplayName("회수가 되돌리다 강제 종료 — 기록이 잡음으로 남아 다음 주기에 다시 집힌다")
    void 회수가_되돌리다_강제_종료되면_다음_주기에_다시_집힌다() {
        String orderId = "order-recovery-crash-705";
        Long productId = 705L;
        int quantity = 5;
        redisTemplate.opsForValue().set(stockKey(productId), String.valueOf(INITIAL_STOCK));

        savePaymentEventWithOrders(orderId, Map.of(productId, quantity));
        PaymentEvent readyEvent = loadEvent(orderId);
        PaymentOrder order = findOrder(readyEvent, productId);

        stockHoldRecordRepository.openHold(orderId, order);
        stockCachePort.decrementAtomic(orderId, order);
        paymentCommandUseCase.expirePayment(readyEvent);

        // given — 되돌리기(캐시 복원)와 닫기(기록 REVERTED) 사이에서 죽는 회수 워커를 흉내낸다.
        // 16b 가 신설한 StockHoldReverter.beforeClose 지연 주입 지점을 그대로 재사용한다.
        StockHoldReverter crashingReverter = new StockHoldReverter(meterRegistry) {
            @Override
            protected void beforeClose(String hookOrderId, PaymentOrder hookOrder) {
                throw new IllegalStateException("회수 워커가 캐시를 되돌린 직후 죽었다고 가정한다");
            }
        };
        StockHoldRecoveryUseCase crashingRecoveryUseCase = new StockHoldRecoveryUseCase(
                stockHoldRecordRepository, paymentEventRepository, stockCachePort, crashingReverter);

        // when — 회수 1회차: 캐시는 복원되지만 기록을 닫기 전에 죽는다.
        assertThatThrownBy(() -> crashingRecoveryUseCase.recover(10))
                .isInstanceOf(IllegalStateException.class);

        // then — 재고는 이미 복원됐지만 기록은 잡음 그대로다.
        assertThat(currentStock(productId)).isEqualTo(INITIAL_STOCK);
        assertThat(stockHoldRecordRepository.findSnapshot(orderId, order).orElseThrow().status())
                .isEqualTo(StockHoldRecordStatus.NOISE);

        // when — 다음 주기: 정상 회수 워커가 같은 기록을 다시 집는다.
        int recoveredCount = stockHoldRecoveryUseCase.recover(10);

        // then — 흔적이 이미 없어 재고를 두 번 복원하지 않고 기록만 닫는다.
        assertThat(recoveredCount).isEqualTo(1);
        assertThat(currentStock(productId))
                .as("이중 복원 없이 그대로 유지된다")
                .isEqualTo(INITIAL_STOCK);
        assertThat(stockHoldRecordRepository.findSnapshot(orderId, order).orElseThrow().status())
                .isEqualTo(StockHoldRecordStatus.REVERTED);
    }

    @Test
    @DisplayName("선점을 쥔 채 강제 종료된 뒤 수명이 지나면, 같은 주문번호 재시도가 선점을 다시 잡아 끝까지 완주한다")
    void 선점을_쥔_채_강제_종료된_뒤_수명이_지나면_재시도가_완주한다() throws InterruptedException {
        String orderId = "order-lock-expire-706";
        Long productId = 706L;
        int quantity = 2;
        long shortLockTtlSeconds = 1L;
        redisTemplate.opsForValue().set(stockKey(productId), String.valueOf(INITIAL_STOCK));

        savePaymentEventWithOrders(orderId, Map.of(productId, quantity));
        PaymentEvent readyEvent = loadEvent(orderId);

        StockCacheRedisAdapter shortLeaseStockCachePort = new StockCacheRedisAdapter(redisTemplate, shortLockTtlSeconds);
        PaymentTransactionCoordinator coordinator = new PaymentTransactionCoordinator(
                mock(PaymentCommandUseCase.class),
                mock(PaymentOutboxUseCase.class),
                shortLeaseStockCachePort,
                stockHoldRecordRepository,
                mock(PaymentConfirmPublisherPort.class));

        // given — 선점만 잡고 죽는다. 명시적 해제도, 상품 반복도 일어나지 않는다.
        Optional<String> lockToken = shortLeaseStockCachePort.acquireOrderLock(orderId);
        assertThat(lockToken).isPresent();

        // 수명이 남아 있는 동안엔 재시도가 선점을 잡지 못하고 물러난다.
        StockDecrementResult contendedRetry =
                coordinator.decrementStock(orderId, readyEvent.getPaymentOrderList());
        assertThat(contendedRetry).isEqualTo(StockDecrementResult.ALREADY_PROCESSING);

        // when — 수명이 지난다. 만료 스케줄러를 거치지 않는 더 빠른 회복 경로다.
        Thread.sleep(Duration.ofSeconds(shortLockTtlSeconds + 1).toMillis());

        // then — 같은 주문번호 재시도가 선점을 다시 잡아 끝까지 완주한다.
        StockDecrementResult recoveredRetry =
                coordinator.decrementStock(orderId, readyEvent.getPaymentOrderList());
        assertThat(recoveredRetry).isEqualTo(StockDecrementResult.SUCCESS);
        assertThat(currentStock(productId)).isEqualTo(INITIAL_STOCK - quantity);
    }

    @Test
    @DisplayName("게이트 값과 상품 DB 값의 차이가 진행 중 선차감 합과 일치한다 (상품별)")
    void 게이트_값과_상품_DB_값의_차이가_진행_중_선차감_합과_일치한다() {
        String orderCommitted = "order-parity-committed-707";
        String orderInProgressA = "order-parity-inprogress-a-707";
        String orderInProgressB = "order-parity-inprogress-b-707";
        Long productId = 707L;
        int quantityCommitted = 2;
        int quantityInProgressA = 3;
        int quantityInProgressB = 4;
        redisTemplate.opsForValue().set(stockKey(productId), String.valueOf(INITIAL_STOCK));

        // 상품 DB 값은 이 테스트가 직접 갱신하지 않는 별도 값으로 취급한다 — 실제로는 상품
        // 서비스가 확정 통지를 받아 비동기로 깎는 값을, 로컬 변수로 흉내낸다.
        int simulatedProductDbStock = INITIAL_STOCK;

        savePaymentEventWithOrders(orderCommitted, Map.of(productId, quantityCommitted));
        savePaymentEventWithOrders(orderInProgressA, Map.of(productId, quantityInProgressA));
        savePaymentEventWithOrders(orderInProgressB, Map.of(productId, quantityInProgressB));

        PaymentOrder committedOrder = findOrder(loadEvent(orderCommitted), productId);
        PaymentOrder inProgressOrderA = findOrder(loadEvent(orderInProgressA), productId);
        PaymentOrder inProgressOrderB = findOrder(loadEvent(orderInProgressB), productId);

        // 한 주문은 확정(COMMITTED)까지 끝난다 — 상품 DB 도 함께 깎였다고 가정한다.
        stockHoldRecordRepository.openHold(orderCommitted, committedOrder);
        stockCachePort.decrementAtomic(orderCommitted, committedOrder);
        stockHoldRecordRepository.commitAllByOrderId(orderCommitted);
        simulatedProductDbStock -= quantityCommitted;

        // 나머지 둘은 아직 진행 중(NOISE)이다 — 상품 DB 는 이 둘의 영향을 아직 받지 않는다.
        stockHoldRecordRepository.openHold(orderInProgressA, inProgressOrderA);
        stockCachePort.decrementAtomic(orderInProgressA, inProgressOrderA);
        stockHoldRecordRepository.openHold(orderInProgressB, inProgressOrderB);
        stockCachePort.decrementAtomic(orderInProgressB, inProgressOrderB);

        // then — 게이트 값과 상품 DB 값의 차이는 아직 진행 중인 선차감 합과 정확히 같다.
        // 확정된 주문은 상품 DB 도 함께 깎였다고 가정했으므로 이 차이에 나타나지 않는다.
        int gateStock = currentStock(productId);
        int expectedInProgressSum = quantityInProgressA + quantityInProgressB;
        assertThat(simulatedProductDbStock - gateStock).isEqualTo(expectedInProgressSum);

        int sumFromNoiseRecords = stockHoldRecordRepository.findNoiseCandidates(10).stream()
                .filter(candidate -> candidate.productId().equals(productId))
                .mapToInt(StockHoldRecordCandidate::quantity)
                .sum();
        assertThat(sumFromNoiseRecords)
                .as("기록 조회로 계산해도 같은 값이 나와야 진행 중 합이 신뢰할 수 있는 근거다")
                .isEqualTo(expectedInProgressSum);
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    private int currentStock(long productId) {
        String value = redisTemplate.opsForValue().get(stockKey(productId));
        if (value == null) {
            throw new IllegalStateException("재고 키가 존재하지 않는다: " + stockKey(productId));
        }
        return Integer.parseInt(value);
    }

    private String stockKey(long productId) {
        return "stock:{" + productId + "}";
    }

    private void savePaymentEventWithOrders(String orderId, Map<Long, Integer> productQuantities) {
        PaymentEventEntity event = PaymentEventEntity.builder()
                .buyerId(BUYER_ID)
                .sellerId(SELLER_ID)
                .orderName("테스트 상품 포함 " + productQuantities.size() + "건")
                .orderId(orderId)
                .gatewayType(PaymentGatewayType.TOSS)
                .status(PaymentEventStatus.READY)
                .lastStatusChangedAt(Instant.now())
                .build();
        PaymentEventEntity savedEvent = jpaPaymentEventRepository.save(event);

        productQuantities.forEach((productId, quantity) -> jpaPaymentOrderRepository.save(
                PaymentOrderEntity.builder()
                        .paymentEventId(savedEvent.getId())
                        .orderId(orderId)
                        .productId(productId)
                        .quantity(quantity)
                        .totalAmount(BigDecimal.valueOf(1_000L * quantity))
                        .status(PaymentOrderStatus.NOT_STARTED)
                        .build()));
    }

    private PaymentEvent loadEvent(String orderId) {
        return paymentEventRepository.findByOrderId(orderId).orElseThrow();
    }

    private PaymentOrder findOrder(PaymentEvent event, Long productId) {
        return event.getPaymentOrderList().stream()
                .filter(order -> order.getProductId().equals(productId))
                .findFirst()
                .orElseThrow();
    }
}
