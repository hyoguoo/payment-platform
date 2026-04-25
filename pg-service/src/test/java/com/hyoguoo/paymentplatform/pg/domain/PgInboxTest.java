package com.hyoguoo.paymentplatform.pg.domain;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PgInbox 도메인 메서드 단위 테스트 (K4 — anemic 도메인 부활).
 *
 * <p>각 메서드의 pre-condition 가드와 상태 전이 결과를 검증한다.
 * FakePgInboxRepository 와의 통합이 아닌 순수 도메인 로직만 테스트.
 */
@DisplayName("PgInbox — 도메인 메서드 상태 전이 가드")
class PgInboxTest {

    private static final String ORDER_ID = "order-k4-test";
    private static final Long AMOUNT = 10000L;

    // =========================================================================
    // markInProgress
    // =========================================================================

    @Test
    @DisplayName("markInProgress — NONE 상태에서 호출 시 IN_PROGRESS 로 전이")
    void markInProgress_WhenStatusNone_ShouldTransitionToInProgress() {
        // given
        PgInbox inbox = PgInbox.create(ORDER_ID, AMOUNT);
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.NONE);

        // when
        inbox.markInProgress();

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("markInProgress — NONE 이 아닌 상태에서 호출 시 IllegalStateException")
    void markInProgress_WhenStatusNotNone_ShouldThrow() {
        // given — IN_PROGRESS 상태
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT,
                null, null, Instant.now(), Instant.now());

        // when / then
        assertThatThrownBy(inbox::markInProgress)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markInProgress — terminal 상태에서 호출 시 IllegalStateException")
    void markInProgress_WhenTerminalStatus_ShouldThrow() {
        // given — APPROVED terminal 상태
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.APPROVED, AMOUNT,
                "result", null, Instant.now(), Instant.now());

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
        // given — NONE 상태
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
        // given — NONE 상태
        PgInbox inbox = PgInbox.create(ORDER_ID, AMOUNT);

        // when / then
        assertThatThrownBy(() -> inbox.markFailed("result", "CODE"))
                .isInstanceOf(IllegalStateException.class);
    }

    // =========================================================================
    // markQuarantined
    // =========================================================================

    @Test
    @DisplayName("markQuarantined — NONE 상태(non-terminal)에서 호출 시 QUARANTINED 전이")
    void markQuarantined_WhenNone_ShouldTransition() {
        // given
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
