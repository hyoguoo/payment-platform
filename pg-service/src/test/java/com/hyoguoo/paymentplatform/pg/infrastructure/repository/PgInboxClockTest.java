package com.hyoguoo.paymentplatform.pg.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PgInbox 도메인 메서드 시간 결정성 검증.
 *
 * <p>fixed Instant 를 파라미터로 전달하면 markInProgress(updatedAt) / markApproved(result, updatedAt) 등이
 * 해당 시각을 updatedAt 으로 정확히 설정해야 한다.
 *
 * <p>PgInboxRepositoryImpl Clock 주입 검증은 JPA 의존 없이 도메인 메서드 시그니처 변경으로 범위를 제한한다.
 */
@DisplayName("PgInbox — 시간 결정성 (fixed Instant 파라미터)")
class PgInboxClockTest {

    private static final String ORDER_ID = "order-k5-inbox-001";
    private static final Long AMOUNT = 10000L;

    /** fixed clock: 2026-04-24T09:00:00Z */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-04-24T09:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    /**
     * PgInbox.create(orderId, amount, now) 로 생성 시 createdAt / updatedAt 이 fixed instant 와 일치.
     */
    @Test
    @DisplayName("create — fixed Instant 파라미터로 생성 시 createdAt/updatedAt 일치")
    void create_withFixedInstant_shouldSetCreatedAtAndUpdatedAt() {
        // when:create(orderId, amount, now) 오버로드 필요
        PgInbox inbox = PgInbox.create(ORDER_ID, AMOUNT, FIXED_INSTANT);

        // then
        assertThat(inbox.getCreatedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(inbox.getUpdatedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.NONE);
    }

    /**
     * markInProgress(updatedAt) 에 fixed Instant 전달 시 updatedAt 일치.
     */
    @Test
    @DisplayName("markInProgress — fixed updatedAt 파라미터 시 updatedAt 일치")
    void markInProgress_withFixedUpdatedAt_shouldSetUpdatedAt() {
        // given
        PgInbox inbox = PgInbox.create(ORDER_ID, AMOUNT, FIXED_INSTANT);

        Instant laterInstant = FIXED_INSTANT.plusSeconds(10);

        // when:markInProgress(updatedAt) 오버로드 필요
        inbox.markInProgress(laterInstant);

        // then
        assertThat(inbox.getUpdatedAt()).isEqualTo(laterInstant);
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
    }

    /**
     * markApproved(result, updatedAt) 에 fixed Instant 전달 시 updatedAt 일치.
     */
    @Test
    @DisplayName("markApproved — fixed updatedAt 파라미터 시 updatedAt 일치")
    void markApproved_withFixedUpdatedAt_shouldSetUpdatedAt() {
        // given — IN_PROGRESS 상태
        PgInbox inbox = PgInbox.of(ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT,
                null, null, FIXED_INSTANT, FIXED_INSTANT);

        Instant laterInstant = FIXED_INSTANT.plusSeconds(20);

        // when:markApproved(result, updatedAt) 오버로드 필요
        inbox.markApproved("vendor-result", laterInstant);

        // then
        assertThat(inbox.getUpdatedAt()).isEqualTo(laterInstant);
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.APPROVED);
    }

    /**
     * markFailed(result, reasonCode, updatedAt) 에 fixed Instant 전달 시 updatedAt 일치.
     */
    @Test
    @DisplayName("markFailed — fixed updatedAt 파라미터 시 updatedAt 일치")
    void markFailed_withFixedUpdatedAt_shouldSetUpdatedAt() {
        // given — IN_PROGRESS 상태
        PgInbox inbox = PgInbox.of(ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT,
                null, null, FIXED_INSTANT, FIXED_INSTANT);

        Instant laterInstant = FIXED_INSTANT.plusSeconds(30);

        // when:markFailed(result, reasonCode, updatedAt) 오버로드 필요
        inbox.markFailed("vendor-fail-result", "VENDOR_REJECTED", laterInstant);

        // then
        assertThat(inbox.getUpdatedAt()).isEqualTo(laterInstant);
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.FAILED);
    }

    /**
     * markQuarantined(result, reasonCode, updatedAt) 에 fixed Instant 전달 시 updatedAt 일치.
     */
    @Test
    @DisplayName("markQuarantined — fixed updatedAt 파라미터 시 updatedAt 일치")
    void markQuarantined_withFixedUpdatedAt_shouldSetUpdatedAt() {
        // given — IN_PROGRESS 상태 (non-terminal)
        PgInbox inbox = PgInbox.of(ORDER_ID, PgInboxStatus.IN_PROGRESS, AMOUNT,
                null, null, FIXED_INSTANT, FIXED_INSTANT);

        Instant laterInstant = FIXED_INSTANT.plusSeconds(40);

        // when:markQuarantined(result, reasonCode, updatedAt) 오버로드 필요
        inbox.markQuarantined(null, "RETRY_EXHAUSTED", laterInstant);

        // then
        assertThat(inbox.getUpdatedAt()).isEqualTo(laterInstant);
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
    }
}
