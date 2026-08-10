package com.hyoguoo.paymentplatform.pg.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgConfirmResultStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayloadSerializer;
import com.hyoguoo.paymentplatform.pg.mock.FakePgGatewayAdapter;
import com.hyoguoo.paymentplatform.pg.mock.FakePgInboxRepository;
import com.hyoguoo.paymentplatform.pg.mock.FakePgOutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PaymentConfirmConsumer(PgConfirmService) 단위 테스트.
 * inbox 5상태 분기와 접수대장(pg_inbox) UNIQUE 단일 층 흡수를 검증한다.
 * domain_risk=true — 동시성 / 재진입 / terminal 재발행 시나리오 모두 커버한다.
 */
@DisplayName("PaymentConfirmConsumer(PgConfirmService)")
class PaymentConfirmConsumerTest {

    private static final String ORDER_ID = "order-001";
    private static final String PAYMENT_KEY = "pk-001";
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(10000);
    private static final String EVENT_UUID = "evt-uuid-001";

    private FakePgInboxRepository inboxRepository;
    private FakePgOutboxRepository outboxRepository;
    private FakePgGatewayAdapter gatewayAdapter;
    private PgInboxPendingService pendingService;
    private PgConfirmService sut;

    @BeforeEach
    void setUp() {
        inboxRepository = new FakePgInboxRepository();
        outboxRepository = new FakePgOutboxRepository();
        gatewayAdapter = new FakePgGatewayAdapter();
        Clock clock = Clock.fixed(Instant.parse("2026-04-21T00:00:00Z"), ZoneOffset.UTC);
        ApplicationEventPublisher eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        ObjectMapper objectMapper = new ObjectMapper();
        // FakePgGatewayAdapter.supports(vendorType)=true 라 selector 가 항상 반환한다.
        PgConfirmStrategySelector selector = new PgConfirmStrategySelector(List.of(gatewayAdapter));
        DuplicateApprovalHandler duplicateApprovalHandler = Mockito.mock(DuplicateApprovalHandler.class);
        PgVendorCallService vendorCallService =
                new PgVendorCallService(inboxRepository, outboxRepository, selector, eventPublisher,
                        new ConfirmedEventPayloadSerializer(objectMapper), objectMapper, clock,
                        duplicateApprovalHandler, new SecureRandom());
        // 접수 기록 삽입 서비스 — 실제 인스턴스를 스파이로 감싸 흡수 시 호출 여부까지 관측한다.
        PgInboxPendingService realPendingService =
                new PgInboxPendingService(inboxRepository, eventPublisher, new SimpleMeterRegistry());
        pendingService = Mockito.spy(realPendingService);
        // terminal 재발행은 별도 빈(PgTerminalReemitService)에 위임한다
        PgTerminalReemitService terminalReemitService = new PgTerminalReemitService(outboxRepository, eventPublisher, clock);
        sut = new PgConfirmService(
                inboxRepository, vendorCallService,
                eventPublisher, clock, pendingService, terminalReemitService);
    }

    // -----------------------------------------------------------------------
    // inbox 없음 → insertPendingAndPublish 호출, PG 호출 0
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — inbox 없을 때 insertPendingAndPublish 위임 (listener 내 벤더 호출 0)")
    void consume_WhenInboxAbsent_ShouldCallInsertPendingAndPublish() {
        // given — inbox 없음 (FakePgInboxRepository 빈 상태)
        PgConfirmCommand command = new PgConfirmCommand(
                ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS, EVENT_UUID);

        // when
        sut.handle(command);

        // then — PG 벤더 호출 0회 (listener 책임: INSERT + ack 까지만)
        assertThat(gatewayAdapter.getConfirmCallCount()).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // IN_PROGRESS → publishEvent 채널 재적재, 벤더 호출 0
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — inbox IN_PROGRESS 존재 시 채널 재적재(publishEvent) 수행, 벤더 호출 0")
    void consume_WhenInboxInProgressAndAttempt2_ShouldPublishEventNotCallVendor() {
        // given — inbox를 IN_PROGRESS 상태로 사전 설정
        PgInbox inProgressInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT.longValue(),
                null, null, Instant.now(), Instant.now());
        inboxRepository.save(inProgressInbox);

        PgConfirmCommand command = new PgConfirmCommand(
                ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS, "evt-uuid-retry-002");

        // when — attempt=2 (self-loop retry → 채널 재적재로 위임)
        sut.handle(command, 2);

        // then — 벤더 호출 0회 (listener는 채널 재적재만, 워커가 처리)
        assertThat(gatewayAdapter.getConfirmCallCount()).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // PENDING → publishEvent 채널 재적재, 벤더 호출 0
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — inbox PENDING 존재 시 채널 재적재, 벤더 호출 0")
    void consume_WhenInboxPending_ShouldPublishEventNotCallVendor() {
        // given — inbox를 PENDING 상태로 사전 설정
        PgInbox pendingInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.PENDING, AMOUNT.longValue(),
                null, null, Instant.now(), Instant.now());
        inboxRepository.save(pendingInbox);

        PgConfirmCommand command = new PgConfirmCommand(
                ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS, "evt-uuid-pending-001");

        // when
        sut.handle(command, 1);

        // then — 벤더 호출 0회
        assertThat(gatewayAdapter.getConfirmCallCount()).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // terminal(APPROVED/FAILED/QUARANTINED) → stored_status_result 재발행
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = PgInboxStatus.class, names = {"APPROVED", "FAILED", "QUARANTINED"})
    @DisplayName("consume — terminal 상태 재수신 시 stored_status_result 로 pg_outbox 재발행하고 벤더 호출은 0회이다")
    void consume_WhenInboxTerminal_ShouldReemitStoredStatus(PgInboxStatus terminalStatus) {
        // given — terminal 상태 inbox
        String storedResult = "{\"orderId\":\"" + ORDER_ID + "\",\"status\":\"" + terminalStatus + "\"}";
        PgInbox terminalInbox = PgInbox.of(
                ORDER_ID, terminalStatus, AMOUNT.longValue(),
                storedResult, null, Instant.now(), Instant.now());
        inboxRepository.save(terminalInbox);

        PgConfirmCommand command = new PgConfirmCommand(
                ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS, "evt-uuid-terminal");

        // when
        sut.handle(command);

        // then — terminal 재수신이므로 벤더 재호출은 없다
        assertThat(gatewayAdapter.getConfirmCallCount()).isEqualTo(0);

        // then — pg_outbox에 재발행 row 생성
        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        PgOutbox reemit = outboxRows.get(0);
        assertThat(reemit.getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
        assertThat(reemit.getPayload()).isEqualTo(storedResult);
    }

    // -----------------------------------------------------------------------
    // 동일 명령 순차 재수신 → 접수 기록 1건, 두 번째 수신은 삽입 서비스 미호출
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — 동일 명령 순차 재수신 시 접수 기록은 1건만 생성되고 두 번째 수신에서는 삽입 서비스가 호출되지 않는다")
    void consume_SequentialDuplicateCommand_ShouldInsertPendingOnlyOnce() {
        // given
        PgConfirmResult successResult = new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS, PAYMENT_KEY, ORDER_ID, AMOUNT, null, null,
                "2026-04-24T01:00:00Z");
        gatewayAdapter.setConfirmResult(ORDER_ID, successResult);

        PgConfirmCommand command = new PgConfirmCommand(
                ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS, EVENT_UUID);

        // when — 동일 명령 순차 2회 수신
        sut.handle(command);
        sut.handle(command);

        // then — 접수 기록은 정확히 1건 (첫 수신에서 PENDING INSERT, 두 번째는 PENDING 라우팅으로 흡수)
        assertThat(inboxRepository.findAll()).hasSize(1);

        // then — 삽입 서비스는 최초 1회만 호출된다 — 접수 기록 수만 보면 흡수된 삽입과
        // 대기 상태 라우팅이 같은 결과로 수렴해 분기 회귀를 못 잡는다
        Mockito.verify(pendingService, Mockito.times(1)).insertPendingAndPublish(
                ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    // -----------------------------------------------------------------------
    // 동일 주문번호 동시 재수신 → 접수 기록 1건 (UNIQUE 흡수)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — 동일 주문번호 동시 재수신 시 접수 기록이 정확히 1건 생성된다")
    void consume_ConcurrentDuplicateCommand_ShouldInsertPendingRecordOnlyOnce() throws InterruptedException {
        // given — inbox 없음 (FakePgInboxRepository 빈 상태)
        int threadCount = 8;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Exception> errors = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            int threadIdx = i;
            Thread.ofVirtual().start(() -> {
                PgConfirmCommand cmd = new PgConfirmCommand(
                        ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS,
                        "evt-concurrent-" + threadIdx);
                try {
                    startLatch.await();
                    sut.handle(cmd);
                } catch (Exception e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // when — 동시 시작
        startLatch.countDown();
        doneLatch.await();

        // then — 접수 기록은 주문번호 UNIQUE 흡수로 정확히 1건
        assertThat(inboxRepository.findAll()).hasSize(1);
        List<Exception> unexpectedErrors = errors.stream()
                .filter(e -> !(e instanceof IllegalStateException))
                .toList();
        assertThat(unexpectedErrors).isEmpty();
    }
}
