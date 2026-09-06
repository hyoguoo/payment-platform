package com.hyoguoo.paymentplatform.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.publisher.PaymentEventPublisher;
import com.hyoguoo.paymentplatform.payment.application.service.PaymentReconciler;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentConfirmResultUseCase;
import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.core.test.ConcurrentActionRunner;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOrderStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentEventEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.entity.PaymentOrderEntity;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.infrastructure.repository.JpaPaymentOrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * 결과 대기(AWAITING_RESULT) 도입 이후, 리컨실러의 조건부 전이와 확정 결과 소비 경로가 실제 DB +
 * 실제 트랜잭션 위에서 서로 덮어쓰지 않는지 통합 검증한다.
 *
 * <p>앞선 태스크들이 리포지토리·유스케이스 단위로 각각 고정한 불변식을, 실제 컴포넌트 조합으로
 * 다시 확인하는 게이트다 — 통과에 구현 변경이 필요 없는 것이 정상이다.
 *
 * <p>처음 세 케이스는 리컨실러 쪽이 쓰는 시점에 상태를 재확인하는 조건부 전이라 순차 기법
 * (읽기 스냅샷을 먼저 뜬 뒤 그 사이 확정을 진행하고, 스냅샷으로 뒤늦게 전이를 시도)으로 결정적으로
 * 재현한다. 반대 방향(격리 이후 도착한 확정)은 읽기부터 쓰기까지 잠금을 계속 들고 있는 것 자체가
 * 검증 대상이라 순차로는 구분되지 않으므로, {@link ConcurrentActionRunner#race}로 실제로 겹쳐
 * 실행한다 — 아웃박스 선점 경합 통합 테스트가 같은 이유로 쓰는 패턴이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@EmbeddedKafka(
        partitions = 1,
        topics = {PaymentTopics.EVENTS_STOCK_COMMITTED},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@DisplayName("결과 대기 경합과 배치 격리 통합 검증")
class AwaitingResultReconcileRaceIntegrationTest {

    private static final Long PRODUCT_ID = 100L;
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(10_000);

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL_CONTAINER =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("payment-awaiting-race-test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
                    .withReuse(true);

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS_CONTAINER =
            new GenericContainer<>("redis:7.2-alpine")
                    .withExposedPorts(6379)
                    .withReuse(true);

    static {
        // @Testcontainers/@Container 를 사용하지 않고 수동 start.
        // @Container 로 관리하면 JUnit5 extension 이 테스트 클래스 완료 후 stop() 을 명시 호출하여
        // withReuse(true) 설정에도 불구하고 컨테이너가 종료된다.
        MYSQL_CONTAINER.start();
        REDIS_CONTAINER.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        // Flyway 활성화 — payment_event_dedupe 테이블이 확정 결과 소비 경로(멱등 마킹)에 필요
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port",
                () -> String.valueOf(REDIS_CONTAINER.getMappedPort(6379)));
        registry.add("payment.cache.stock-redis.host", REDIS_CONTAINER::getHost);
        registry.add("payment.cache.stock-redis.port",
                () -> String.valueOf(REDIS_CONTAINER.getMappedPort(6379)));
        // 리컨실러는 이 테스트에서 scan() 을 명시 호출한다 — 백그라운드 스케줄러는 끈다.
        registry.add("scheduler.enabled", () -> "false");
    }

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private PaymentCommandUseCase paymentCommandUseCase;

    @Autowired
    private PaymentConfirmResultUseCase paymentConfirmResultUseCase;

    @Autowired
    private PaymentReconciler paymentReconciler;

    @Autowired
    private JpaPaymentEventRepository jpaPaymentEventRepository;

    @Autowired
    private JpaPaymentOrderRepository jpaPaymentOrderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 되돌리기·격리 전이가 감사 이벤트를 실제로 발행/생략하는지 확인하기 위한 spy. */
    @MockitoSpyBean
    private PaymentEventPublisher paymentEventPublisher;

    @BeforeEach
    void setUp() {
        jpaPaymentOrderRepository.deleteAllInBatch();
        jpaPaymentEventRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM payment_event_dedupe");
    }

    @AfterEach
    void tearDown() {
        jpaPaymentOrderRepository.deleteAllInBatch();
        jpaPaymentEventRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM payment_event_dedupe");
    }

    @Test
    @DisplayName("리컨실러가 IN_PROGRESS 를 읽은 뒤 그 사이 확정되면, 되돌리기가 확정을 덮어쓰지 않는다")
    void 리컨실러가_읽은_뒤_확정되면_되돌리지_않는다() {
        // given — IN_PROGRESS 결제. 리컨실러의 1차 스캔이 이 시점에 읽은 스냅샷을 흉내낸다.
        String orderId = "order-race-inprogress-done-" + UUID.randomUUID();
        Instant now = Instant.now();
        Long eventId = saveEvent(orderId, PaymentEventStatus.IN_PROGRESS, now, now);
        saveOrder(eventId, orderId);

        PaymentEvent staleSnapshot = paymentEventRepository.findByOrderId(orderId).orElseThrow();

        // when — 리컨실러가 스냅샷을 들고 전이를 시도하기 전에, 별도로 읽은 최신 인스턴스로
        // 확정 결과가 먼저 도착해 완료 처리된다.
        PaymentEvent freshForConfirm = paymentEventRepository.findByOrderId(orderId).orElseThrow();
        paymentCommandUseCase.markPaymentAsDone(freshForConfirm, Instant.now());
        verify(paymentEventPublisher, times(1)).publishStatusChange(any(), any(), any(), any());

        PaymentEvent result = paymentCommandUseCase.resetPaymentToAwaitingResult(staleSnapshot);

        // then — CAS 가 0건으로 끝나 null 을 반환하고, 확정 결과를 덮어쓰지 않는다.
        assertThat(result).as("그 사이 확정된 건과의 CAS 충돌은 null 을 반환한다").isNull();

        PaymentEventEntity finalEvent = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
        assertThat(finalEvent.getStatus())
                .as("확정 결과가 되돌리기 시도에 덮어써지지 않는다")
                .isEqualTo(PaymentEventStatus.DONE);

        List<PaymentOrderEntity> orders = jpaPaymentOrderRepository.findByPaymentEventId(eventId);
        assertThat(orders).extracting(PaymentOrderEntity::getStatus).containsOnly(PaymentOrderStatus.SUCCESS);

        // then — 스킵된 되돌리기는 감사 이력을 남기지 않는다(발행 호출 횟수가 늘지 않는다).
        verify(paymentEventPublisher, times(1)).publishStatusChange(any(), any(), any(), any());
    }

    @Test
    @DisplayName("리컨실러가 AWAITING_RESULT 를 읽고 격리하려는 사이 확정되면, 격리가 확정을 덮어쓰지 않는다")
    void 리컨실러가_격리하려는_사이_확정되면_격리하지_않는다() {
        // given — 결과 대기 결제. 잘못 격리되면 되돌릴 길이 없어 특히 중요한 케이스다.
        String orderId = "order-race-awaiting-done-" + UUID.randomUUID();
        Instant now = Instant.now();
        Long eventId = saveEvent(orderId, PaymentEventStatus.AWAITING_RESULT, now, now);
        saveOrder(eventId, orderId);

        PaymentEvent staleSnapshot = paymentEventRepository.findByOrderId(orderId).orElseThrow();

        // when — 격리를 시도하기 전에, 별도로 읽은 최신 인스턴스로 확정 결과가 먼저 도착한다.
        PaymentEvent freshForConfirm = paymentEventRepository.findByOrderId(orderId).orElseThrow();
        paymentCommandUseCase.markPaymentAsDone(freshForConfirm, Instant.now());
        verify(paymentEventPublisher, times(1)).publishStatusChange(any(), any(), any(), any());

        PaymentEvent result = paymentCommandUseCase.quarantinePaymentAutomatically(
                staleSnapshot, "AWAITING_RESULT_TIMEOUT");

        // then
        assertThat(result)
                .as("그 사이 확정된 건과의 CAS 충돌은 null 을 반환하고 격리로 옮기지 않는다")
                .isNull();

        PaymentEventEntity finalEvent = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
        assertThat(finalEvent.getStatus())
                .as("잘못된 격리 시도가 확정 결과를 덮어쓰지 않는다")
                .isEqualTo(PaymentEventStatus.DONE);

        List<PaymentOrderEntity> orders = jpaPaymentOrderRepository.findByPaymentEventId(eventId);
        assertThat(orders).extracting(PaymentOrderEntity::getStatus).containsOnly(PaymentOrderStatus.SUCCESS);

        verify(paymentEventPublisher, times(1)).publishStatusChange(any(), any(), any(), any());
    }

    @Test
    @DisplayName("결과 대기 결제에 승인 결과가 도착하면 완료로 전이한다")
    void 결과_대기_결제에_승인_결과가_도착하면_완료된다() {
        // given — 이 설계가 도입되기 전에는 이 상태에서 승인 결과가 not-retryable 예외로 즉시 DLQ 로
        // 빠졌다. 지금은 결과 대기가 확정 결과를 적용 가능한 상태로 분류되어 정상 완료로 이어진다.
        String orderId = "order-awaiting-approved-" + UUID.randomUUID();
        Instant now = Instant.now();
        Long eventId = saveEvent(orderId, PaymentEventStatus.AWAITING_RESULT, now, now);
        saveOrder(eventId, orderId);

        ConfirmedEventMessage message = approvedMessage(orderId, AMOUNT.longValue());

        // when
        paymentConfirmResultUseCase.handle(message);

        // then
        PaymentEventEntity finalEvent = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
        assertThat(finalEvent.getStatus())
                .as("결과 대기에서도 승인 결과를 받아들여 완료로 전이한다")
                .isEqualTo(PaymentEventStatus.DONE);

        List<PaymentOrderEntity> orders = jpaPaymentOrderRepository.findByPaymentEventId(eventId);
        assertThat(orders).extracting(PaymentOrderEntity::getStatus).containsOnly(PaymentOrderStatus.SUCCESS);
    }

    @RepeatedTest(30)
    @DisplayName("격리 CAS 와 확정 컨슈머가 같은 결제를 동시에 다투면, 먼저 잠근 쪽만 반영되고 진 쪽은 흔적 없이 물러난다")
    void 격리_이후_도착한_확정_결과가_그_격리를_덮어쓰지_않는다() {
        // given — 결과 대기 결제. 순차 기법으로는 "격리가 이미 커밋된 뒤 확정이 오는" 경우만 재현되고
        // 그건 기존 종결 가드가 이미 거른다 — 여기서 검증할 성질(잠금을 쥔 채로 유지)은 실제로
        // 겹쳐야만 구분된다.
        String orderId = "order-race-quarantine-vs-confirm-" + UUID.randomUUID();
        Instant now = Instant.now();
        Long eventId = saveEvent(orderId, PaymentEventStatus.AWAITING_RESULT, now, now);
        saveOrder(eventId, orderId);

        ConfirmedEventMessage approvedMessage = approvedMessage(orderId, AMOUNT.longValue());
        AtomicReference<PaymentEvent> quarantineResult = new AtomicReference<>();

        // when — 한 스레드는 승인 확정 메시지를 처리하고, 다른 스레드는 같은 주문에 자동 격리
        // 전이를 시도하게 해 실제로 겹친다.
        ConcurrentActionRunner.race(
                () -> paymentConfirmResultUseCase.handle(approvedMessage),
                () -> {
                    PaymentEvent forQuarantine = paymentEventRepository.findByOrderId(orderId).orElseThrow();
                    quarantineResult.set(paymentCommandUseCase.quarantinePaymentAutomatically(
                            forQuarantine, "AWAITING_RESULT_TIMEOUT"));
                }
        );

        // then — 어느 쪽이 이기든 최종 상태와 CAS 결과가 서로 어긋나지 않는다.
        PaymentEventEntity finalEvent = jpaPaymentEventRepository.findByOrderId(orderId).orElseThrow();
        List<PaymentOrderEntity> orders = jpaPaymentOrderRepository.findByPaymentEventId(eventId);

        if (finalEvent.getStatus() == PaymentEventStatus.QUARANTINED) {
            assertThat(quarantineResult.get())
                    .as("격리가 이겼다면 격리 CAS 는 성공(non-null)이어야 한다")
                    .isNotNull();
            assertThat(orders).extracting(PaymentOrderEntity::getStatus).containsOnly(PaymentOrderStatus.EXECUTING);
        } else {
            assertThat(finalEvent.getStatus())
                    .as("격리가 졌다면 확정 컨슈머가 이겨 완료로 남는다 — 격리가 완료를 덮어쓰지 않는다")
                    .isEqualTo(PaymentEventStatus.DONE);
            assertThat(quarantineResult.get())
                    .as("확정이 이겼다면 격리 CAS 는 0건(null)으로 끝난다")
                    .isNull();
            assertThat(orders).extracting(PaymentOrderEntity::getStatus).containsOnly(PaymentOrderStatus.SUCCESS);
        }
    }

    @Test
    @DisplayName("배치 스캔 중 한 건이 그 사이 확정과 경합해도, 관계없는 나머지 stale 건은 계속 처리된다")
    void 배치_중_한_건이_경합해도_나머지가_처리된다() {
        // given — 둘 다 1차 임계를 넘긴 stale IN_PROGRESS
        Instant staleExecutedAt = Instant.now().minusSeconds(600);

        String racedOrderId = "order-batch-raced-" + UUID.randomUUID();
        Long racedEventId = saveEvent(racedOrderId, PaymentEventStatus.IN_PROGRESS, staleExecutedAt, staleExecutedAt);
        saveOrder(racedEventId, racedOrderId);

        String remainingOrderId = "order-batch-remaining-" + UUID.randomUUID();
        Long remainingEventId = saveEvent(
                remainingOrderId, PaymentEventStatus.IN_PROGRESS, staleExecutedAt, staleExecutedAt);
        saveOrder(remainingEventId, remainingOrderId);

        // when — 스캔과, 그 중 한 건을 그 사이 확정시키는 원시 갱신을 실제로 겹쳐 실행한다.
        // 스캔이 원시 갱신보다 먼저 그 행을 잠그면 정상 전이되고, 원시 갱신이 먼저면 CAS 가 0건으로
        // 끝난다 — 어느 쪽이든 나머지 건 처리를 막지 않아야 한다.
        ConcurrentActionRunner.race(
                paymentReconciler::scan,
                () -> jdbcTemplate.update("UPDATE payment_event SET status = 'DONE' WHERE id = ?", racedEventId)
        );

        // then — 경합한 건은 스캔이 이겼으면 결과 대기, 원시 갱신이 이겼으면 확정 — 둘 중 하나로 끝난다.
        PaymentEventEntity racedEntity = jpaPaymentEventRepository.findById(racedEventId).orElseThrow();
        assertThat(racedEntity.getStatus())
                .as("경합한 건은 스캔의 결과 대기 전이 또는 그 사이 확정된 완료 중 하나로 결정적으로 끝난다")
                .isIn(PaymentEventStatus.AWAITING_RESULT, PaymentEventStatus.DONE);

        // then — 경합과 무관한 나머지 건은 경합 결과와 상관없이 배치가 멈추지 않고 항상 처리된다.
        PaymentEventEntity remainingEntity = jpaPaymentEventRepository.findById(remainingEventId).orElseThrow();
        assertThat(remainingEntity.getStatus())
                .as("경합과 무관한 나머지 건은 배치가 멈추지 않고 결과 대기로 옮겨진다")
                .isEqualTo(PaymentEventStatus.AWAITING_RESULT);
    }

    // ── 픽스처 헬퍼 ─────────────────────────────────────────────────────────────

    private Long saveEvent(String orderId, PaymentEventStatus status, Instant executedAt, Instant lastStatusChangedAt) {
        PaymentEventEntity event = PaymentEventEntity.builder()
                .buyerId(1L)
                .sellerId(2L)
                .orderName("경합 검증 상품 — " + orderId)
                .orderId(orderId)
                .paymentKey("pay-key-" + orderId)
                .gatewayType(PaymentGatewayType.TOSS)
                .status(status)
                .executedAt(executedAt)
                .lastStatusChangedAt(lastStatusChangedAt)
                .build();
        return jpaPaymentEventRepository.save(event).getId();
    }

    private void saveOrder(Long paymentEventId, String orderId) {
        PaymentOrderEntity order = PaymentOrderEntity.builder()
                .paymentEventId(paymentEventId)
                .orderId(orderId)
                .productId(PRODUCT_ID)
                .quantity(1)
                .totalAmount(AMOUNT)
                .status(PaymentOrderStatus.EXECUTING)
                .build();
        jpaPaymentOrderRepository.save(order);
    }

    private static ConfirmedEventMessage approvedMessage(String orderId, Long amount) {
        String approvedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        return new ConfirmedEventMessage(orderId, "APPROVED", null, amount, approvedAt, UUID.randomUUID().toString());
    }
}
