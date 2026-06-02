package com.hyoguoo.paymentplatform.pg.domain;

import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
    // factory 4종 시그니처 + 필드 매핑 검증 (builder 회귀 방어)
    // =========================================================================

    @Test
    @DisplayName("create — paymentKey + vendorType 포함 5-arg 오버로드 → status=PENDING, paymentKey/vendorType 세팅")
    void create_withPaymentKeyAndVendorType_startsPending() {
        // when
        Instant now = Instant.now();
        PgInbox inbox = PgInbox.create(ORDER_ID, AMOUNT, now, "pay-key-001", "TOSS_PAYMENTS");

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.PENDING);
        assertThat(inbox.getPaymentKey()).isEqualTo("pay-key-001");
        assertThat(inbox.getVendorType()).isEqualTo("TOSS_PAYMENTS");
        assertThat(inbox.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(inbox.getAmount()).isEqualTo(AMOUNT);
        assertThat(inbox.getId()).isNull();
    }

    @Test
    @DisplayName("create — Instant now 인자 오버로드 → status=PENDING, paymentKey/vendorType null")
    void create_withInstantNow_startsPending() {
        // when
        Instant now = Instant.now();
        PgInbox inbox = PgInbox.create(ORDER_ID, AMOUNT, now);

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.PENDING);
        assertThat(inbox.getPaymentKey()).isNull();
        assertThat(inbox.getVendorType()).isNull();
        assertThat(inbox.getId()).isNull();
    }

    @Test
    @DisplayName("createDirectTerminal — terminal status → 성공 + storedStatusResult 세팅")
    void createDirectTerminal_approvedStatus_succeeds() {
        // given
        String storedResult = "{\"status\":\"DONE\"}";
        Instant now = Instant.now();

        // when
        PgInbox inbox = PgInbox.createDirectTerminal(ORDER_ID, AMOUNT, now, PgInboxStatus.APPROVED, storedResult);

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.APPROVED);
        assertThat(inbox.getStoredStatusResult()).isEqualTo(storedResult);
        assertThat(inbox.getId()).isNull();
    }

    @Test
    @DisplayName("createDirectTerminal — non-terminal status 전달 시 IllegalArgumentException (가드 보존)")
    void createDirectTerminal_nonTerminalStatus_throwsIllegalArgument() {
        // when / then — 도메인 가드: test 픽스처 이중화 목적 (main 보호는 어댑터 가드 담당)
        Instant now = Instant.now();
        assertThatThrownBy(() ->
                PgInbox.createDirectTerminal(ORDER_ID, AMOUNT, now, PgInboxStatus.PENDING, "result"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("of — 7-arg 오버로드 → 모든 필드 정확 매핑 (builder 전환 후 silent corruption 방지)")
    void of_sevenArg_constructsCorrectly() {
        // given
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Instant updated = Instant.parse("2026-01-01T01:00:00Z");

        // when — transitDirectToTerminal 어댑터 경로와 동일한 7-arg of 호출
        PgInbox inbox = PgInbox.of(
                ORDER_ID,
                PgInboxStatus.APPROVED,
                AMOUNT,
                "{\"status\":\"DONE\"}",
                "REASON_CODE",
                created,
                updated);

        // then
        assertThat(inbox.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.APPROVED);
        assertThat(inbox.getAmount()).isEqualTo(AMOUNT);
        assertThat(inbox.getStoredStatusResult()).isEqualTo("{\"status\":\"DONE\"}");
        assertThat(inbox.getReasonCode()).isEqualTo("REASON_CODE");
        assertThat(inbox.getCreatedAt()).isEqualTo(created);
        assertThat(inbox.getUpdatedAt()).isEqualTo(updated);
        assertThat(inbox.getId()).isNull();
        assertThat(inbox.getPaymentKey()).isNull();
        assertThat(inbox.getVendorType()).isNull();
    }

    @Test
    @DisplayName("ofWithId — id 포함 전체 10-arg → getId() == id (JPA 어댑터 toDomain 경로)")
    void ofWithId_includesId() {
        // given
        Instant now = Instant.now();

        // when
        PgInbox inbox = PgInbox.ofWithId(
                42L,
                ORDER_ID,
                PgInboxStatus.IN_PROGRESS,
                AMOUNT,
                null,
                null,
                now,
                now,
                "pay-key-ofwithid",
                "NICE_PAY");

        // then
        assertThat(inbox.getId()).isEqualTo(42L);
        assertThat(inbox.getPaymentKey()).isEqualTo("pay-key-ofwithid");
        assertThat(inbox.getVendorType()).isEqualTo("NICE_PAY");
    }

    // =========================================================================
    // create — PENDING 시작
    // =========================================================================

    @Test
    @DisplayName("create — Instant now 인자 → status=PENDING + createdAt/updatedAt 세팅")
    void create_startsWithPendingStatus() {
        // when
        Instant now = Instant.now();
        PgInbox inbox = PgInbox.create(ORDER_ID, AMOUNT, now);

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.PENDING);
        assertThat(inbox.getCreatedAt()).isEqualTo(now);
        assertThat(inbox.getUpdatedAt()).isEqualTo(now);
    }

    // =========================================================================
    // createDirectInProgress — 보정 경로 PENDING 우회
    // =========================================================================

    @Test
    @DisplayName("createDirectInProgress — 보정 경로 정적 팩토리 → status=IN_PROGRESS 직진 (PENDING 우회)")
    void createDirectInProgress_statusIsInProgress() {
        // when
        Instant now = Instant.now();
        PgInbox inbox = PgInbox.createDirectInProgress(ORDER_ID, AMOUNT, now);

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
        assertThat(inbox.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(inbox.getAmount()).isEqualTo(AMOUNT);
    }

    // =========================================================================
    // createDirectTerminal — 보정 경로 terminal 신설
    // =========================================================================

    @Test
    @DisplayName("createDirectTerminal — APPROVED → status=APPROVED + storedStatusResult 세팅")
    void createDirectTerminal_approved_statusIsApproved() {
        // given
        String storedResult = "{\"status\":\"DONE\"}";
        Instant now = Instant.now();

        // when
        PgInbox inbox = PgInbox.createDirectTerminal(ORDER_ID, AMOUNT, now, PgInboxStatus.APPROVED, storedResult);

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.APPROVED);
        assertThat(inbox.getStoredStatusResult()).isEqualTo(storedResult);
    }

    @Test
    @DisplayName("createDirectTerminal — QUARANTINED → status=QUARANTINED + storedStatusResult 세팅")
    void createDirectTerminal_quarantined_statusIsQuarantined() {
        // given
        String storedResult = "{\"status\":\"QUARANTINED\"}";
        Instant now = Instant.now();

        // when
        PgInbox inbox = PgInbox.createDirectTerminal(ORDER_ID, AMOUNT, now, PgInboxStatus.QUARANTINED, storedResult);

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
        Instant now = Instant.now();
        PgInbox inbox = PgInbox.create(ORDER_ID, AMOUNT, now);
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.PENDING);

        // when
        Instant laterInstant = now.plusSeconds(5);
        inbox.markInProgress(laterInstant);

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
        assertThat(inbox.getUpdatedAt()).isEqualTo(laterInstant);
    }

    @ParameterizedTest
    @EnumSource(value = PgInboxStatus.class, names = {"IN_PROGRESS", "APPROVED", "FAILED", "QUARANTINED"})
    @DisplayName("markInProgress — 비-PENDING 상태에서 호출 시 IllegalStateException")
    void markInProgress_fromNonPending_throws(PgInboxStatus nonPendingStatus) {
        // given
        Instant now = Instant.now();
        PgInbox inbox = PgInbox.of(
                ORDER_ID, nonPendingStatus, AMOUNT,
                null, null, now, now);

        // when / then
        assertThatThrownBy(() -> inbox.markInProgress(now.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    // =========================================================================
    // markApproved
    // =========================================================================

    @Test
    @DisplayName("markApproved — IN_PROGRESS 상태에서 호출 시 APPROVED 전이 + storedStatusResult 설정")
    void markApproved_WhenStatusInProgress_ShouldTransitionAndSetResult() {
        // given
        Instant now = Instant.now();
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT,
                null, null, now, now);

        // when
        Instant updatedAt = now.plusSeconds(1);
        inbox.markApproved("vendor-approve-json", updatedAt);

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.APPROVED);
        assertThat(inbox.getStoredStatusResult()).isEqualTo("vendor-approve-json");
    }

    @Test
    @DisplayName("markApproved — IN_PROGRESS 가 아닌 상태에서 호출 시 IllegalStateException")
    void markApproved_WhenStatusNotInProgress_ShouldThrow() {
        // given — PENDING 상태
        Instant now = Instant.now();
        PgInbox inbox = PgInbox.create(ORDER_ID, AMOUNT, now);

        // when / then
        assertThatThrownBy(() -> inbox.markApproved("result", now))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markApproved — terminal 상태에서 호출 시 IllegalStateException")
    void markApproved_WhenTerminalStatus_ShouldThrow() {
        // given — FAILED terminal 상태
        Instant now = Instant.now();
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.FAILED, AMOUNT,
                "old-result", "VENDOR_REJECTED", now, now);

        // when / then
        assertThatThrownBy(() -> inbox.markApproved("new-result", now))
                .isInstanceOf(IllegalStateException.class);
    }

    // =========================================================================
    // markFailed
    // =========================================================================

    @Test
    @DisplayName("markFailed — IN_PROGRESS 상태에서 호출 시 FAILED 전이 + result + reasonCode 설정")
    void markFailed_WhenStatusInProgress_ShouldTransitionAndSetResultAndReasonCode() {
        // given
        Instant now = Instant.now();
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT,
                null, null, now, now);

        // when
        Instant updatedAt = now.plusSeconds(1);
        inbox.markFailed("vendor-fail-json", "VENDOR_REJECTED", updatedAt);

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.FAILED);
        assertThat(inbox.getStoredStatusResult()).isEqualTo("vendor-fail-json");
        assertThat(inbox.getReasonCode()).isEqualTo("VENDOR_REJECTED");
    }

    @Test
    @DisplayName("markFailed — IN_PROGRESS 가 아닌 상태에서 호출 시 IllegalStateException")
    void markFailed_WhenStatusNotInProgress_ShouldThrow() {
        // given — PENDING 상태
        Instant now = Instant.now();
        PgInbox inbox = PgInbox.create(ORDER_ID, AMOUNT, now);

        // when / then
        assertThatThrownBy(() -> inbox.markFailed("result", "CODE", now))
                .isInstanceOf(IllegalStateException.class);
    }

    // =========================================================================
    // markQuarantined
    // =========================================================================

    @Test
    @DisplayName("markQuarantined — PENDING 상태(non-terminal)에서 호출 시 QUARANTINED 전이")
    void markQuarantined_WhenPending_ShouldTransition() {
        // given — PENDING 시작
        Instant now = Instant.now();
        PgInbox inbox = PgInbox.create(ORDER_ID, AMOUNT, now);

        // when
        Instant updatedAt = now.plusSeconds(1);
        inbox.markQuarantined("QUARANTINED_RESULT", "RETRY_EXHAUSTED", updatedAt);

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(inbox.getStoredStatusResult()).isEqualTo("QUARANTINED_RESULT");
        assertThat(inbox.getReasonCode()).isEqualTo("RETRY_EXHAUSTED");
    }

    @Test
    @DisplayName("markQuarantined — IN_PROGRESS(non-terminal)에서 호출 시 QUARANTINED 전이")
    void markQuarantined_WhenInProgress_ShouldTransition() {
        // given
        Instant now = Instant.now();
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT,
                null, null, now, now);

        // when
        inbox.markQuarantined(null, "DLQ_OVERFLOW", now.plusSeconds(1));

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(inbox.getReasonCode()).isEqualTo("DLQ_OVERFLOW");
    }

    @Test
    @DisplayName("markQuarantined — 이미 terminal 상태이면 IllegalStateException")
    void markQuarantined_WhenAlreadyTerminal_ShouldThrow() {
        // given — APPROVED terminal 상태
        Instant now = Instant.now();
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.APPROVED, AMOUNT,
                "result", null, now, now);

        // when / then
        assertThatThrownBy(() -> inbox.markQuarantined(null, "DLQ", now))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markQuarantined — QUARANTINED terminal 상태이면 IllegalStateException")
    void markQuarantined_WhenAlreadyQuarantined_ShouldThrow() {
        // given — QUARANTINED terminal 상태
        Instant now = Instant.now();
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.QUARANTINED, AMOUNT,
                null, "OLD_REASON", now, now);

        // when / then
        assertThatThrownBy(() -> inbox.markQuarantined(null, "NEW_REASON", now))
                .isInstanceOf(IllegalStateException.class);
    }

    // =========================================================================
    // T8 — 고정 Instant 인자 주입 결정성 테스트
    // =========================================================================

    @Test
    @DisplayName("markInProgress — 고정 Instant 인자 주입 → updatedAt 결정성 단정 (T8 D2)")
    void markInProgress_withFixedInstant_setsUpdatedAt() {
        // given
        Instant fixedInstant = Instant.parse("2026-06-01T00:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);
        Instant now = fixedClock.instant();
        PgInbox inbox = PgInbox.create(ORDER_ID, AMOUNT, now, "pay-key", "TOSS");

        // when
        Instant laterInstant = fixedInstant.plusSeconds(10);
        inbox.markInProgress(laterInstant);

        // then
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
        assertThat(inbox.getUpdatedAt()).isEqualTo(laterInstant);
    }

    @Test
    @DisplayName("create — 고정 Instant 인자 주입 → createdAt/updatedAt 결정성 단정 (T8 D2)")
    void create_withFixedInstant_setsCreatedAt() {
        // given
        Instant fixedInstant = Instant.parse("2026-06-01T00:00:00Z");

        // when
        PgInbox inbox = PgInbox.create(ORDER_ID, AMOUNT, fixedInstant);

        // then
        assertThat(inbox.getCreatedAt()).isEqualTo(fixedInstant);
        assertThat(inbox.getUpdatedAt()).isEqualTo(fixedInstant);
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.PENDING);
    }
}
