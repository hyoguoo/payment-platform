package com.hyoguoo.paymentplatform.pg.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PgInboxProcessor 단위 테스트 — PCS-8.
 *
 * <p>domain_risk=true: TX_A → 벤더 HTTP → TX_B 시퀀스 검증.
 * - processPending: PENDING→IN_PROGRESS CAS (SKIP LOCKED) + invokeVendor + applyOutcome
 * - processInProgressZombie: IN_PROGRESS row 사용 + invokeVendor + applyOutcome
 * - 예외 경로: 0 row → 즉시 return (벤더 호출 0)
 * - 벤더 예외 → applyOutcome 미호출
 * - ALREADY_PROCESSED → applyOutcome 의 HandledInternally 분기 → DuplicateApprovalHandler 위임 (C-F2/PC-F4 흡수)
 */
@DisplayName("PgInboxProcessor")
@ExtendWith(MockitoExtension.class)
class PgInboxProcessorTest {

    private static final Long INBOX_ID = 42L;
    private static final String ORDER_ID = "order-processor-001";
    private static final Instant NOW = Instant.parse("2026-05-09T00:00:00Z");

    @Mock
    private PgInboxRepository inboxRepository;

    @Mock
    private PgVendorCallService vendorCallService;

    private PgInboxProcessor sut;

    @BeforeEach
    void setUp() {
        sut = new PgInboxProcessor(inboxRepository, vendorCallService);
    }

    // -----------------------------------------------------------------------
    // processPending
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("processPending — PENDING row 존재 + transitPendingToInProgress true → invokeVendor 1회 + applyOutcome 1회")
    void processPending_pendingRow_callsInvokeVendorAndApplyOutcome() {
        // given
        PgInbox pendingInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.PENDING, 15000L, null, null, NOW, NOW);
        when(inboxRepository.findById(INBOX_ID)).thenReturn(Optional.of(pendingInbox));
        when(inboxRepository.transitPendingToInProgress(INBOX_ID)).thenReturn(true);
        when(vendorCallService.invokeVendor(any())).thenReturn(new GatewayOutcome.Success(null));

        // when
        sut.processPending(INBOX_ID);

        // then
        verify(vendorCallService, times(1)).invokeVendor(any());
        verify(vendorCallService, times(1)).applyOutcome(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("processPending — transitPendingToInProgress false (0 row = 선점됨) → invokeVendor 미호출")
    void processPending_noRow_returnsWithoutVendorCall() {
        // given
        PgInbox pendingInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.PENDING, 15000L, null, null, NOW, NOW);
        when(inboxRepository.findById(INBOX_ID)).thenReturn(Optional.of(pendingInbox));
        when(inboxRepository.transitPendingToInProgress(INBOX_ID)).thenReturn(false);

        // when
        sut.processPending(INBOX_ID);

        // then — 벤더 호출 0
        verify(vendorCallService, never()).invokeVendor(any());
        verify(vendorCallService, never()).applyOutcome(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("processPending — findById empty (이미 처리됨/없음) → invokeVendor 미호출")
    void processPending_inboxAbsent_returnsWithoutVendorCall() {
        // given
        when(inboxRepository.findById(INBOX_ID)).thenReturn(Optional.empty());

        // when
        sut.processPending(INBOX_ID);

        // then — 벤더 호출 0, transitPendingToInProgress 미호출
        verify(inboxRepository, never()).transitPendingToInProgress(any());
        verify(vendorCallService, never()).invokeVendor(any());
    }

    @Test
    @DisplayName("processPending — invokeVendor RuntimeException → applyOutcome 미호출, pg_inbox IN_PROGRESS 잔존 (좀비 폴링 회수)")
    void processPending_vendorThrows_inboxRemainsInProgress() {
        // given
        PgInbox pendingInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.PENDING, 15000L, null, null, NOW, NOW);
        when(inboxRepository.findById(INBOX_ID)).thenReturn(Optional.of(pendingInbox));
        when(inboxRepository.transitPendingToInProgress(INBOX_ID)).thenReturn(true);
        when(vendorCallService.invokeVendor(any())).thenThrow(new RuntimeException("network error"));

        // when — RuntimeException 전파
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> sut.processPending(INBOX_ID));

        // then — applyOutcome 미호출 (pg_inbox IN_PROGRESS 잔존 → 좀비 폴링 회수)
        verify(vendorCallService, times(1)).invokeVendor(any());
        verify(vendorCallService, never()).applyOutcome(any(), any(), anyInt(), any());
    }

    // -----------------------------------------------------------------------
    // processInProgressZombie
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("processInProgressZombie — IN_PROGRESS row 존재 → invokeVendor 1회 + applyOutcome 1회")
    void processInProgressZombie_inProgressRow_callsInvokeVendorAndApplyOutcome() {
        // given
        PgInbox inProgressInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, 15000L, null, null, NOW, NOW);
        when(inboxRepository.findById(INBOX_ID)).thenReturn(Optional.of(inProgressInbox));
        when(vendorCallService.invokeVendor(any())).thenReturn(new GatewayOutcome.Success(null));

        // when
        sut.processInProgressZombie(INBOX_ID);

        // then
        verify(vendorCallService, times(1)).invokeVendor(any());
        verify(vendorCallService, times(1)).applyOutcome(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("processInProgressZombie — findById empty (0 row) → invokeVendor 미호출")
    void processInProgressZombie_noRow_returnsWithoutVendorCall() {
        // given
        when(inboxRepository.findById(INBOX_ID)).thenReturn(Optional.empty());

        // when
        sut.processInProgressZombie(INBOX_ID);

        // then
        verify(vendorCallService, never()).invokeVendor(any());
        verify(vendorCallService, never()).applyOutcome(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("processInProgressZombie — 벤더가 HandledInternally(ALREADY_PROCESSED) 반환 → applyOutcome 의 5분기 HandledInternally → DuplicateApprovalHandler 1회 위임 (C-F2/PC-F4 흡수)")
    void processInProgressZombie_vendorReturnsAlreadyProcessed_delegatesToDuplicateApprovalHandler() {
        // given
        PgInbox inProgressInbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, 15000L, null, null, NOW, NOW);
        when(inboxRepository.findById(INBOX_ID)).thenReturn(Optional.of(inProgressInbox));
        GatewayOutcome handledInternally = new GatewayOutcome.HandledInternally("ALREADY_PROCESSED");
        when(vendorCallService.invokeVendor(any())).thenReturn(handledInternally);

        // when
        sut.processInProgressZombie(INBOX_ID);

        // then — applyOutcome 1회 호출 — HandledInternally 분기가 DuplicateApprovalHandler 에 위임 (PCS-6 검증)
        verify(vendorCallService, times(1)).invokeVendor(any());
        verify(vendorCallService, times(1)).applyOutcome(
                eq(handledInternally), any(), anyInt(), any());
    }

    @Test
    @DisplayName("processInProgressZombie_lockHeld_skipsSilently — selectInProgressForUpdateSkipLocked 빈 결과 → invokeVendor 미호출 (M4)")
    void processInProgressZombie_lockHeld_skipsSilently() {
        // given: selectInProgressForUpdateSkipLocked 가 empty 반환 (락 선점 실패)
        when(inboxRepository.selectInProgressForUpdateSkipLocked(INBOX_ID)).thenReturn(Optional.empty());

        // when
        sut.processInProgressZombie(INBOX_ID);

        // then — invokeVendor 미호출 (다른 워커가 이미 처리 중)
        verify(vendorCallService, never()).invokeVendor(any());
        verify(vendorCallService, never()).applyOutcome(any(), any(), anyInt(), any());
        // findById 는 더 이상 호출되지 않음 (selectInProgressForUpdateSkipLocked 로 대체)
        verify(inboxRepository, never()).findById(any());
    }
}
