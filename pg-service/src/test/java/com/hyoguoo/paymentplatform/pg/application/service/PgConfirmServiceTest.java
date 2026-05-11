package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.domain.event.PgInboxReadyEvent;
import com.hyoguoo.paymentplatform.pg.application.port.out.EventDedupeStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PgConfirmService 단위 테스트 (PCS-9 분기 재배치 검증).
 *
 * <p>분기 재배치:
 * <ul>
 *   <li>inbox 없음 (absent) → {@link PgInboxPendingService#insertPendingAndPublish} 호출</li>
 *   <li>PENDING inbox → publishEvent(PgInboxReadyEvent), insertPending 0회</li>
 *   <li>IN_PROGRESS inbox → publishEvent(PgInboxReadyEvent), insertPending 0회</li>
 *   <li>terminal inbox → {@link PgTerminalReemitService#reemit} 1회 호출 (M2: self-invocation 우회)</li>
 * </ul>
 */
@DisplayName("PgConfirmService (PCS-9 분기 재배치)")
class PgConfirmServiceTest {

    private static final String ORDER_ID = "order-pcs9-001";
    private static final String PAYMENT_KEY = "pk-pcs9-001";
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(20000L);
    private static final String EVENT_UUID = "evt-pcs9-uuid-001";
    private static final String VENDOR_TYPE_STR = "TOSS";

    private PgInboxRepository pgInboxRepository;
    private PgVendorCallService pgVendorCallService;
    private EventDedupeStore eventDedupeStore;
    private ApplicationEventPublisher applicationEventPublisher;
    private PgInboxPendingService pgInboxPendingService;
    private PgTerminalReemitService pgTerminalReemitService;
    private PgConfirmService sut;

    @BeforeEach
    void setUp() {
        pgInboxRepository = mock(PgInboxRepository.class);
        pgVendorCallService = mock(PgVendorCallService.class);
        eventDedupeStore = mock(EventDedupeStore.class);
        applicationEventPublisher = mock(ApplicationEventPublisher.class);
        pgInboxPendingService = mock(PgInboxPendingService.class);
        pgTerminalReemitService = mock(PgTerminalReemitService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-05-09T00:00:00Z"), ZoneOffset.UTC);

        // dedupe 항상 통과 (markSeen=true, remove no-op)
        when(eventDedupeStore.markSeen(anyString())).thenReturn(true);

        sut = new PgConfirmService(
                pgInboxRepository, pgVendorCallService,
                eventDedupeStore, applicationEventPublisher, clock,
                pgInboxPendingService, pgTerminalReemitService);
    }

    // -----------------------------------------------------------------------
    // TC1: inbox 없음 → insertPendingAndPublish 호출
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handle — inbox 없을 때 PgInboxPendingService.insertPendingAndPublish 1회 호출")
    void handle_absentInbox_callsInsertPendingAndPublish() {
        // given
        when(pgInboxRepository.findByOrderId(ORDER_ID)).thenReturn(java.util.Optional.empty());
        when(pgInboxPendingService.insertPendingAndPublish(
                anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        PgConfirmCommand command = new PgConfirmCommand(
                ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS, EVENT_UUID);

        // when
        sut.handle(command, 1);

        // then
        verify(pgInboxPendingService, times(1)).insertPendingAndPublish(
                anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    // -----------------------------------------------------------------------
    // TC2: inbox 없음 → 벤더 호출 0 (A1 acceptance)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handle — inbox 없을 때 PgConfirmPort.confirm 미호출 (A1 acceptance)")
    void handle_absentInbox_doesNotCallVendor() {
        // given
        when(pgInboxRepository.findByOrderId(ORDER_ID)).thenReturn(java.util.Optional.empty());
        when(pgInboxPendingService.insertPendingAndPublish(
                anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        PgConfirmCommand command = new PgConfirmCommand(
                ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS, EVENT_UUID);

        // when
        sut.handle(command, 1);

        // then — 벤더 호출 0회
        verify(pgVendorCallService, never()).callVendor(any(), anyInt(), any());
        verify(pgVendorCallService, never()).invokeVendor(any());
    }

    // -----------------------------------------------------------------------
    // TC3: PENDING inbox → publishEvent 1회, insertPending 0회
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handle — PENDING inbox 존재 시 publishEvent 1회, insertPendingAndPublish 미호출")
    void handle_pendingInbox_publishesEventWithoutInsert() {
        // given
        PgInbox pendingInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.PENDING, AMOUNT.longValue(),
                null, null, Instant.now(), Instant.now());
        when(pgInboxRepository.findByOrderId(ORDER_ID))
                .thenReturn(java.util.Optional.of(pendingInbox));

        PgConfirmCommand command = new PgConfirmCommand(
                ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS, "evt-pending-001");

        // when
        sut.handle(command, 1);

        // then
        verify(applicationEventPublisher, times(1)).publishEvent(any(PgInboxReadyEvent.class));
        verify(pgInboxPendingService, never()).insertPendingAndPublish(
                anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    // -----------------------------------------------------------------------
    // TC4: IN_PROGRESS inbox → publishEvent 1회, insertPending 0회
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handle — IN_PROGRESS inbox 존재 시 publishEvent 1회, insertPendingAndPublish 미호출")
    void handle_inProgressInbox_publishesEventWithoutInsert() {
        // given
        PgInbox inProgressInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT.longValue(),
                null, null, Instant.now(), Instant.now());
        when(pgInboxRepository.findByOrderId(ORDER_ID))
                .thenReturn(java.util.Optional.of(inProgressInbox));

        PgConfirmCommand command = new PgConfirmCommand(
                ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS, "evt-inprog-001");

        // when
        sut.handle(command, 1);

        // then
        verify(applicationEventPublisher, times(1)).publishEvent(any(PgInboxReadyEvent.class));
        verify(pgInboxPendingService, never()).insertPendingAndPublish(
                anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    // -----------------------------------------------------------------------
    // TC5: terminal inbox → outbox INSERT 1회, 벤더 호출 0
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("handle — terminal inbox 재수신 시 PgTerminalReemitService.reemit 1회 호출, 벤더 호출 0 (M2)")
    void handle_terminalInbox_reemitsStoredStatus() {
        // given
        String storedResult = "{\"orderId\":\"" + ORDER_ID + "\",\"status\":\"APPROVED\"}";
        PgInbox approvedInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.APPROVED, AMOUNT.longValue(),
                storedResult, null, Instant.now(), Instant.now());
        when(pgInboxRepository.findByOrderId(ORDER_ID))
                .thenReturn(java.util.Optional.of(approvedInbox));
        doNothing().when(pgTerminalReemitService).reemit(any());

        PgConfirmCommand command = new PgConfirmCommand(
                ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS, "evt-terminal-001");

        // when
        sut.handle(command, 1);

        // then — PgTerminalReemitService.reemit 1회 호출 (M2: 외부 빈 위임으로 self-invocation 해소)
        verify(pgTerminalReemitService, times(1)).reemit(any(PgInbox.class));
        // then — 벤더 호출 0
        verify(pgVendorCallService, never()).callVendor(any(), anyInt(), any());
        verify(pgVendorCallService, never()).invokeVendor(any());
    }
}
