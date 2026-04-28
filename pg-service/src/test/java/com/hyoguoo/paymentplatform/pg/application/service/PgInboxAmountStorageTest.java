package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.mock.FakePgInboxRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PgInboxAmountService 단위 테스트.
 * business inbox amount 컬럼 저장 규약을 검증한다.
 *
 * <p>pg_inbox.amount 는 아래 3가지 경로로만 기록된다 — 다른 경로의 직접 UPDATE 금지.
 * <ul>
 *   <li>(a) NONE→IN_PROGRESS: command payload amount (BigDecimal scale=0 검증 후 long 변환)</li>
 *   <li>(b) IN_PROGRESS→APPROVED: 벤더 2자 대조 통과값 (inbox.amount == vendorAmount)</li>
 *   <li>(c) NONE→APPROVED 직접 전이: payload vs vendor 2자 대조 통과값</li>
 * </ul>
 */
@DisplayName("PgInboxAmountService — amount 저장 규약")
class PgInboxAmountStorageTest {

    private static final String ORDER_ID = "order-amount-001";
    private static final long STORED_AMOUNT = 15000L;

    private FakePgInboxRepository inboxRepository;
    private PgInboxAmountService sut;

    @BeforeEach
    void setUp() {
        inboxRepository = new FakePgInboxRepository();
        sut = new PgInboxAmountService(inboxRepository);
    }

    // -----------------------------------------------------------------------
    // TC1: (a) NONE→IN_PROGRESS 경로 — payload amount 기록 (BigDecimal scale=0)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("recordPayloadAmount — scale=0 BigDecimal 입력 시 pg_inbox.amount 기록 (불변식 4c-a)")
    void storeInbox_WhenNoneToInProgress_ShouldRecordPayloadAmount() {
        // given — scale=0 BigDecimal (원화 정수)
        BigDecimal payloadAmount = new BigDecimal("15000");

        // when
        sut.recordPayloadAmount(ORDER_ID, payloadAmount);

        // then — inbox.amount == 15000 (payload 그대로 기록)
        PgInbox inbox = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(inbox.getAmount()).isEqualTo(15000L);
        assertThat(inbox.getStatus()).isEqualTo(PgInboxStatus.IN_PROGRESS);
    }

    // -----------------------------------------------------------------------
    // TC2: (b) IN_PROGRESS→APPROVED 경로 — 2자 금액 대조 통과값 저장
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("validateAndApprove — inbox.amount == vendorAmount 일치 시 APPROVED 전이 (불변식 4c-b)")
    void storeInbox_WhenApproved_ShouldPassTwoWayAmountCheck() {
        // given — inbox IN_PROGRESS + amount=15000
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, STORED_AMOUNT,
                null, null, Instant.now(), Instant.now());
        inboxRepository.save(inbox);

        // when — vendorAmount == inbox.amount → 2자 대조 통과
        sut.validateAndApprove(ORDER_ID, STORED_AMOUNT);

        // then — APPROVED 전이 완료
        PgInbox updated = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PgInboxStatus.APPROVED);
        assertThat(updated.getAmount()).isEqualTo(STORED_AMOUNT);
    }

    // -----------------------------------------------------------------------
    // TC3: (b) 2자 불일치 → QUARANTINED + AMOUNT_MISMATCH
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("validateAndApprove — inbox.amount != vendorAmount 불일치 시 QUARANTINED + AMOUNT_MISMATCH (불변식 4c 위반)")
    void storeInbox_WhenApproved_WhenAmountMismatch_ShouldQuarantine() {
        // given — inbox IN_PROGRESS + amount=15000
        PgInbox inbox = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, STORED_AMOUNT,
                null, null, Instant.now(), Instant.now());
        inboxRepository.save(inbox);

        // when — vendorAmount != inbox.amount (벤더 응답이 다름)
        long mismatchedVendorAmount = 99999L;
        sut.validateAndApprove(ORDER_ID, mismatchedVendorAmount);

        // then — QUARANTINED 전이 + AMOUNT_MISMATCH reason_code
        PgInbox updated = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PgInboxStatus.QUARANTINED);
        assertThat(updated.getReasonCode()).isEqualTo("AMOUNT_MISMATCH");
    }

    // -----------------------------------------------------------------------
    // TC4: scale>0 BigDecimal 입력 → ArithmeticException 거부
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("recordPayloadAmount — scale>0 BigDecimal 입력 시 ArithmeticException 거부 (BigDecimal 변환 규약)")
    void storeInbox_WhenBigDecimalScaleNotZero_ShouldReject() {
        // given — scale=2 BigDecimal (소수점 포함 — 원화 규약 위반)
        BigDecimal fractionalAmount = new BigDecimal("150.50");

        // when/then — ArithmeticException 발생 (메시지는 "integral" 또는 "scale" 무관, 타입만 검증)
        assertThatThrownBy(() -> sut.recordPayloadAmount(ORDER_ID, fractionalAmount))
                .isInstanceOf(ArithmeticException.class);

        // given — 음수 BigDecimal (도메인 불변식 위반)
        BigDecimal negativeAmount = new BigDecimal("-1000");

        // when/then — ArithmeticException 또는 IllegalArgumentException 발생
        assertThatThrownBy(() -> sut.recordPayloadAmount(ORDER_ID, negativeAmount))
                .isInstanceOfAny(ArithmeticException.class, IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }
}
