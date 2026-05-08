package com.hyoguoo.paymentplatform.pg.domain;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PgInbox 도메인 메서드 단위 테스트.
 *
 * <p>각 메서드의 pre-condition 가드와 상태 전이 결과를 검증한다.
 * FakePgInboxRepository 와의 통합이 아닌 순수 도메인 로직만 테스트.
 */
@DisplayName("PgInbox — 도메인 메서드 상태 전이 가드")
class PgInboxTest {

    private static final String ORDER_ID = "order-k4-test";
    private static final Long AMOUNT = 10000L;

    // =========================================================================
    // PCS-2: create — PENDING 시작
    // =========================================================================

    @Test
    @DisplayName("create — orderId + amount 로 생성 시 status=PENDING + receivedAt 세팅")
    void create_startsWithPendingStatus() {
        // when
        Instant before = Instant.now();
        PgInbox inbox = PgInbox.create(ORDER_ID, AMOUNT);
        Instant after = Instant.now();

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.PENDING);
        assertThat(inbox.getCreatedAt()).isBetween(before, after);
        assertThat(inbox.getUpdatedAt()).isBetween(before, after);
    }

    // =========================================================================
    // PCS-2: createDirectInProgress — 보정 경로 PENDING 우회
    // =========================================================================

    @Test
    @DisplayName("createDirectInProgress — 보정 경로 정적 팩토리 → status=IN_PROGRESS 직진 (PENDING 우회)")
    void createDirectInProgress_statusIsInProgress() {
        // when
        PgInbox inbox = PgInbox.createDirectInProgress(ORDER_ID, AMOUNT);

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
        assertThat(inbox.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(inbox.getAmount()).isEqualTo(AMOUNT);
    }

    // =========================================================================
    // PCS-2: createDirectTerminal — 보정 경로 terminal 신설
    // =========================================================================

    @Test
    @DisplayName("createDirectTerminal — APPROVED → status=APPROVED + storedStatusResult 세팅")
    void createDirectTerminal_approved_statusIsApproved() {
        // given
        String storedResult = "{\"status\":\"DONE\"}";

        // when
        PgInbox inbox = PgInbox.createDirectTerminal(ORDER_ID, AMOUNT, PgInboxStatus.APPROVED, storedResult);

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.APPROVED);
        assertThat(inbox.getStoredStatusResult()).isEqualTo(storedResult);
    }

    @Test
    @DisplayName("createDirectTerminal — QUARANTINED → status=QUARANTINED + storedStatusResult 세팅")
    void createDirectTerminal_quarantined_statusIsQuarantined() {
        // given
        String storedResult = "{\"status\":\"QUARANTINED\"}";

        // when
        PgInbox inbox = PgInbox.createDirectTerminal(ORDER_ID, AMOUNT, PgInboxStatus.QUARANTINED, storedResult);

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(inbox.getStoredStatusResult()).isEqualTo(storedResult);
    }

    // =========================================================================
    // markInProgress
    // =========================================================================

    @Test
    @DisplayName("markInProgress — PENDING 상태에서 호출 시 IN_PROGRESS 로 전이")
    void markInProgress_fromPending_succeeds() {
        // given
        PgInbox inbox = PgInbox.create(ORDER_ID, AMOUNT);
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.PENDING);

        // when
        Instant before = Instant.now();
        inbox.markInProgress();
        Instant after = Instant.now();

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
        assertThat(inbox.getUpdatedAt()).isBetween(before, after);
    }

    @ParameterizedTest
    @EnumSource(value = PgInboxStatus.class, names = {"IN_PROGRESS", "APPROVED", "FAILED", "QUARANTINED"})
    @DisplayName("markInProgress — 비-PENDING 상태에서 호출 시 IllegalStateException")
    void markInProgress_fromNonPending_throws(PgInboxStatus nonPendingStatus) {
        // given
        PgInbox inbox = PgInbox.of(
                ORDER_ID, nonPendingStatus, AMOUNT,
                null, null, Instant.now(), Instant.now());

        // when / then
        assertThatThrownBy(inbox::markInProgress)
                .isInstanceOf(IllegalStateException.class);
    }

    // =========================================================================
    // markApproved
    // =========================================================================

    @Test
    @DisplayName("markApproved — IN_PROGRESS 상태에서 호출 시 APPROVED 전이 + storedStatusResult 설정")
    void markApproved_WhenStatusInProgress_ShouldTransitionAndSetResult() {
        // given
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT,
                null, null, Instant.now(), Instant.now());

        // when
        inbox.markApproved("vendor-approve-json");

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.APPROVED);
        assertThat(inbox.getStoredStatusResult()).isEqualTo("vendor-approve-json");
    }

    @Test
    @DisplayName("markApproved — IN_PROGRESS 가 아닌 상태에서 호출 시 IllegalStateException")
    void markApproved_WhenStatusNotInProgress_ShouldThrow() {
        // given — PENDING 상태 (NONE 폐기 후 create 는 PENDING 시작)
        PgInbox inbox = PgInbox.create(ORDER_ID, AMOUNT);

        // when / then
        assertThatThrownBy(() -> inbox.markApproved("result"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markApproved — terminal 상태에서 호출 시 IllegalStateException")
    void markApproved_WhenTerminalStatus_ShouldThrow() {
        // given — FAILED terminal 상태
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.FAILED, AMOUNT,
                "old-result", "VENDOR_REJECTED", Instant.now(), Instant.now());

        // when / then
        assertThatThrownBy(() -> inbox.markApproved("new-result"))
                .isInstanceOf(IllegalStateException.class);
    }

    // =========================================================================
    // markFailed
    // =========================================================================

    @Test
    @DisplayName("markFailed — IN_PROGRESS 상태에서 호출 시 FAILED 전이 + result + reasonCode 설정")
    void markFailed_WhenStatusInProgress_ShouldTransitionAndSetResultAndReasonCode() {
        // given
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT,
                null, null, Instant.now(), Instant.now());

        // when
        inbox.markFailed("vendor-fail-json", "VENDOR_REJECTED");

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.FAILED);
        assertThat(inbox.getStoredStatusResult()).isEqualTo("vendor-fail-json");
        assertThat(inbox.getReasonCode()).isEqualTo("VENDOR_REJECTED");
    }

    @Test
    @DisplayName("markFailed — IN_PROGRESS 가 아닌 상태에서 호출 시 IllegalStateException")
    void markFailed_WhenStatusNotInProgress_ShouldThrow() {
        // given — PENDING 상태 (NONE 폐기 후 create 는 PENDING 시작)
        PgInbox inbox = PgInbox.create(ORDER_ID, AMOUNT);

        // when / then
        assertThatThrownBy(() -> inbox.markFailed("result", "CODE"))
                .isInstanceOf(IllegalStateException.class);
    }

    // =========================================================================
    // markQuarantined
    // =========================================================================

    @Test
    @DisplayName("markQuarantined — PENDING 상태(non-terminal)에서 호출 시 QUARANTINED 전이")
    void markQuarantined_WhenPending_ShouldTransition() {
        // given — NONE 폐기 후 create 는 PENDING 시작
        PgInbox inbox = PgInbox.create(ORDER_ID, AMOUNT);

        // when
        inbox.markQuarantined("QUARANTINED_RESULT", "RETRY_EXHAUSTED");

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(inbox.getStoredStatusResult()).isEqualTo("QUARANTINED_RESULT");
        assertThat(inbox.getReasonCode()).isEqualTo("RETRY_EXHAUSTED");
    }

    @Test
    @DisplayName("markQuarantined — IN_PROGRESS(non-terminal)에서 호출 시 QUARANTINED 전이")
    void markQuarantined_WhenInProgress_ShouldTransition() {
        // given
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT,
                null, null, Instant.now(), Instant.now());

        // when
        inbox.markQuarantined(null, "DLQ_OVERFLOW");

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(inbox.getReasonCode()).isEqualTo("DLQ_OVERFLOW");
    }

    @Test
    @DisplayName("markQuarantined — 이미 terminal 상태이면 IllegalStateException (불변식 6c)")
    void markQuarantined_WhenAlreadyTerminal_ShouldThrow() {
        // given — APPROVED terminal 상태
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.APPROVED, AMOUNT,
                "result", null, Instant.now(), Instant.now());

        // when / then
        assertThatThrownBy(() -> inbox.markQuarantined(null, "DLQ"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markQuarantined — QUARANTINED terminal 상태이면 IllegalStateException")
    void markQuarantined_WhenAlreadyQuarantined_ShouldThrow() {
        // given — QUARANTINED terminal 상태
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.QUARANTINED, AMOUNT,
                null, "OLD_REASON", Instant.now(), Instant.now());

        // when / then
        assertThatThrownBy(() -> inbox.markQuarantined(null, "NEW_REASON"))
                .isInstanceOf(IllegalStateException.class);
    }
}
