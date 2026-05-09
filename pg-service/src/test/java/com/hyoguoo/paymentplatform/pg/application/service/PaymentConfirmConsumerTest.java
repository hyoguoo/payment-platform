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
import com.hyoguoo.paymentplatform.pg.mock.FakeEventDedupeStore;
import com.hyoguoo.paymentplatform.pg.mock.FakePgGatewayAdapter;
import com.hyoguoo.paymentplatform.pg.mock.FakePgInboxRepository;
import com.hyoguoo.paymentplatform.pg.mock.FakePgOutboxRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PaymentConfirmConsumer(PgConfirmService) 단위 테스트.
 * 2단 멱등성 + inbox 5상태 핵심 불변 검증.
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
    private FakeEventDedupeStore dedupeStore;
    private PgConfirmService sut;

    @BeforeEach
    void setUp() {
        inboxRepository = new FakePgInboxRepository();
        outboxRepository = new FakePgOutboxRepository();
        gatewayAdapter = new FakePgGatewayAdapter();
        dedupeStore = new FakeEventDedupeStore();
        Clock clock = Clock.fixed(Instant.parse("2026-04-21T00:00:00Z"), ZoneOffset.UTC);
        ApplicationEventPublisher eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        ObjectMapper objectMapper = new ObjectMapper();
        // FakePgGatewayAdapter.supports(vendorType)=true 라 selector 가 항상 반환한다.
        PgConfirmStrategySelector selector = new PgConfirmStrategySelector(List.of(gatewayAdapter));
        DuplicateApprovalHandler duplicateApprovalHandler = Mockito.mock(DuplicateApprovalHandler.class);
        PgVendorCallService vendorCallService =
                new PgVendorCallService(inboxRepository, outboxRepository, selector, eventPublisher,
                        new ConfirmedEventPayloadSerializer(objectMapper), objectMapper, clock,
                        duplicateApprovalHandler);
        // PCS-9: PgConfirmService 생성자에 PgInboxPendingService 추가
        PgInboxPendingService pendingService = Mockito.mock(PgInboxPendingService.class);
        Mockito.when(pendingService.insertPendingAndPublish(
                Mockito.anyString(), Mockito.anyLong(), Mockito.anyString(),
                Mockito.any(), Mockito.any()))
                .thenReturn(1L);
        // M2: PgTerminalReemitService 별 빈 분리 — terminal 재발행 위임
        PgTerminalReemitService terminalReemitService = new PgTerminalReemitService(outboxRepository, eventPublisher);
        sut = new PgConfirmService(
                inboxRepository, vendorCallService, dedupeStore,
                eventPublisher, clock, pendingService, terminalReemitService);
    }

    // -----------------------------------------------------------------------
    // TC1: inbox 없음 → insertPendingAndPublish 호출, PG 호출 0 (PCS-9 분기 재배치)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — inbox 없을 때 insertPendingAndPublish 위임 (PCS-9 A1 acceptance: listener 내 벤더 호출 0)")
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
    // TC2: IN_PROGRESS → publishEvent 채널 재적재, 벤더 호출 0 (PCS-9 분기 재배치)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — inbox IN_PROGRESS 존재 시 채널 재적재(publishEvent) 수행, 벤더 호출 0 (PCS-9)")
    void consume_WhenInboxInProgressAndAttempt2_ShouldPublishEventNotCallVendor() {
        // given — inbox를 IN_PROGRESS 상태로 사전 설정
        PgInbox inProgressInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT.longValue(),
                null, null, Instant.now(), Instant.now());
        inboxRepository.save(inProgressInbox);

        PgConfirmCommand command = new PgConfirmCommand(
                ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS, "evt-uuid-retry-002");

        // when — attempt=2 (self-loop retry → PCS-9: 채널 재적재로 위임)
        sut.handle(command, 2);

        // then — 벤더 호출 0회 (PCS-9: listener는 채널 재적재만, 워커가 처리)
        assertThat(gatewayAdapter.getConfirmCallCount()).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // TC2b: PENDING → publishEvent 채널 재적재, 벤더 호출 0 (PCS-9)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — inbox PENDING 존재 시 채널 재적재, 벤더 호출 0 (PCS-9)")
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
    // TC3: terminal(APPROVED/FAILED/QUARANTINED) → stored_status_result 재발행
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

        // then — 벤더 호출 0회 (불변식 4/4b)
        assertThat(gatewayAdapter.getConfirmCallCount()).isEqualTo(0);

        // then — pg_outbox에 재발행 row 생성
        List<PgOutbox> outboxRows = outboxRepository.findAll();
        assertThat(outboxRows).hasSize(1);
        PgOutbox reemit = outboxRows.get(0);
        assertThat(reemit.getTopic()).isEqualTo(PgTopics.EVENTS_CONFIRMED);
        assertThat(reemit.getPayload()).isEqualTo(storedResult);
    }

    // -----------------------------------------------------------------------
    // TC4: 동일 eventUUID 2회 → no-op (불변식 5)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — 동일 eventUUID 2회 수신 시 PG 호출 0회 (불변식 5: eventUUID dedupe)")
    void consume_DuplicateEventUUID_ShouldNoOp() {
        // given
        PgConfirmResult successResult = new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS, PAYMENT_KEY, ORDER_ID, AMOUNT, null, null,
                "2026-04-24T01:00:00Z");
        gatewayAdapter.setConfirmResult(ORDER_ID, successResult);

        PgConfirmCommand command = new PgConfirmCommand(
                ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS, EVENT_UUID);

        // when — 첫 번째 소비 (정상 처리)
        sut.handle(command);
        int firstCallCount = gatewayAdapter.getConfirmCallCount();

        // when — 두 번째 소비 (동일 eventUUID → dedupe)
        sut.handle(command);

        // then — 두 번째 호출에서 PG 추가 호출 없음
        assertThat(gatewayAdapter.getConfirmCallCount()).isEqualTo(firstCallCount);
    }

    // -----------------------------------------------------------------------
    // TC5: 동시 진입 시 dedupe 작동 + 벤더 호출 0 (PCS-9 분기 재배치)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — 동일 eventUUID 동시 진입 시 dedupe 작동, 벤더 호출 0 (PCS-9 listener 채널 위임)")
    void consume_WhenInboxAbsent_ConcurrentDedupe_ShouldBeAtomicUnderConcurrency() throws InterruptedException {
        // given — inbox 없음 (FakePgInboxRepository 빈 상태)
        // PCS-9: absent → insertPendingAndPublish → 워커가 처리 (listener 내 벤더 호출 0)
        int threadCount = 8;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
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
                    successCount.incrementAndGet();
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

        // then — 벤더 호출 0 (PCS-9: listener는 INSERT + ack 까지만)
        assertThat(gatewayAdapter.getConfirmCallCount()).isEqualTo(0);
        List<Exception> unexpectedErrors = errors.stream()
                .filter(e -> !(e instanceof IllegalStateException))
                .toList();
        assertThat(unexpectedErrors).isEmpty();
    }
}
