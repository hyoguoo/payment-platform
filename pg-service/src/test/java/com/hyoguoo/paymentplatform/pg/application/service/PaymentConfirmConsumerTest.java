package com.hyoguoo.paymentplatform.pg.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.PgOutbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgConfirmResultStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.event.ConfirmedEventPayloadSerializer;
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
 * ADR-04(2단 멱등성) + ADR-21(inbox 5상태) 핵심 불변 검증.
 * domain_risk=true: 동시성·재진입·terminal 재발행 시나리오 모두 커버.
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
        PgVendorCallService vendorCallService =
                new PgVendorCallService(inboxRepository, outboxRepository, gatewayAdapter, eventPublisher,
                        new ConfirmedEventPayloadSerializer(objectMapper), objectMapper, clock);
        sut = new PgConfirmService(
                inboxRepository, outboxRepository, vendorCallService, dedupeStore, eventPublisher, clock);
    }

    // -----------------------------------------------------------------------
    // TC1: NONE → IN_PROGRESS + PG 호출 1회
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — inbox NONE 상태일 때 IN_PROGRESS 전이 후 PG 호출을 1회 수행한다")
    void consume_WhenInboxNone_ShouldTransitToInProgressAndCallVendor() {
        // given
        PgConfirmResult successResult = new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS, PAYMENT_KEY, ORDER_ID, AMOUNT, null, null,
                "2026-04-24T01:00:00Z");
        gatewayAdapter.setConfirmResult(ORDER_ID, successResult);

        PgConfirmCommand command = new PgConfirmCommand(
                ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS, EVENT_UUID);

        // when
        sut.handle(command);

        // then — PG 호출 1회
        assertThat(gatewayAdapter.getConfirmCallCount()).isEqualTo(1);

        // then — inbox 상태 전이 확인
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        // NONE → IN_PROGRESS 전이 후 PG 호출 완료 → 최종 상태는 저장된 상태
        assertThat(inbox.getStatus()).isNotEqualTo(PgInboxStatus.NONE);
    }

    // -----------------------------------------------------------------------
    // TC2: IN_PROGRESS → no-op (불변식 4b)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — inbox IN_PROGRESS 상태일 때 no-op 처리한다 (불변식 4b)")
    void consume_WhenInboxInProgress_ShouldNoOp() {
        // given — inbox를 IN_PROGRESS 상태로 사전 설정
        PgInbox inProgressInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT.longValue(),
                null, null, Instant.now(), Instant.now());
        inboxRepository.save(inProgressInbox);

        PgConfirmCommand command = new PgConfirmCommand(
                ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS, "evt-uuid-002");

        // when
        sut.handle(command);

        // then — PG 호출 없음 (no-op)
        assertThat(gatewayAdapter.getConfirmCallCount()).isEqualTo(0);

        // then — inbox 상태 변경 없음
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
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
    // TC5: 동시 진입 시 IN_PROGRESS 전이 원자성
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("consume — 동시 진입 시 IN_PROGRESS 전이는 단 1회만 성공한다 (원자성)")
    void consume_WhenInboxNoneToInProgress_ShouldBeAtomicUnderConcurrency() throws InterruptedException {
        // given
        PgConfirmResult successResult = new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS, PAYMENT_KEY, ORDER_ID, AMOUNT, null, null,
                "2026-04-24T01:00:00Z");
        gatewayAdapter.setConfirmResult(ORDER_ID, successResult);

        int threadCount = 8;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> errors = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            int threadIdx = i;
            Thread.ofVirtual().start(() -> {
                // 각 스레드는 서로 다른 eventUUID로 소비 시도 (dedupe 우회)
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

        // then — PG 호출은 정확히 1회 (IN_PROGRESS 전이 성공한 스레드만 호출)
        assertThat(gatewayAdapter.getConfirmCallCount()).isEqualTo(1);

        // then — inbox 상태는 IN_PROGRESS 이후 상태 (NONE 아님)
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getStatus()).isNotEqualTo(PgInboxStatus.NONE);
    }
}
