package com.hyoguoo.paymentplatform.pg.infrastructure.messaging.event;

import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * ConfirmedEventPayload 단위 테스트.
 * T-A1: amount + approvedAt 필드 추가 RED 단계.
 *
 * <p>APPROVED 팩토리: amount/approvedAt non-null 강제.
 * FAILED/QUARANTINED 팩토리: 두 필드 null 허용.
 */
@DisplayName("ConfirmedEventPayload")
class ConfirmedEventPayloadTest {

    private static final String ORDER_ID = "order-001";
    private static final String EVENT_UUID = "evt-uuid-001";
    private static final Long AMOUNT = 15000L;
    private static final String APPROVED_AT = "2026-04-24T10:00:00+09:00";

    // -----------------------------------------------------------------------
    // TC1: approved() — amount null 시 NullPointerException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("approved — amount=null 이면 NullPointerException")
    void approved_withAmount_nullAmount_shouldThrow() {
        assertThatNullPointerException()
                .isThrownBy(() -> ConfirmedEventPayload.approved(ORDER_ID, EVENT_UUID, null, APPROVED_AT));
    }

    // -----------------------------------------------------------------------
    // TC2: approved() — approvedAt null 시 NullPointerException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("approved — approvedAt=null 이면 NullPointerException")
    void approved_withApprovedAt_nullApprovedAt_shouldThrow() {
        assertThatNullPointerException()
                .isThrownBy(() -> ConfirmedEventPayload.approved(ORDER_ID, EVENT_UUID, AMOUNT, null));
    }

    // -----------------------------------------------------------------------
    // TC3: approved() — 정상 호출 시 amount/approvedAt 값 일치
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("approved — 정상 호출 시 amount/approvedAt 필드 일치")
    void approved_validArgs_returnsPayloadWithFields() {
        ConfirmedEventPayload payload = ConfirmedEventPayload.approved(ORDER_ID, EVENT_UUID, AMOUNT, APPROVED_AT);

        assertThat(payload.amount()).isEqualTo(AMOUNT);
        assertThat(payload.approvedAt()).isEqualTo(APPROVED_AT);
        assertThat(payload.orderId()).isEqualTo(ORDER_ID);
        assertThat(payload.status()).isEqualTo("APPROVED");
    }

    // -----------------------------------------------------------------------
    // TC4: failed() — amount/approvedAt null (두 필드 nullable)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("failed — amount=null, approvedAt=null 허용")
    void failed_doesNotRequireAmount() {
        ConfirmedEventPayload payload = ConfirmedEventPayload.failed(ORDER_ID, "CARD_DECLINED", EVENT_UUID);

        assertThat(payload.amount()).isNull();
        assertThat(payload.approvedAt()).isNull();
        assertThat(payload.status()).isEqualTo("FAILED");
    }
}
