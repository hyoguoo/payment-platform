package com.hyoguoo.paymentplatform.pg.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayloadSerializer;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgConfirmResultStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.mock.FakePgGatewayAdapterNicepay;
import com.hyoguoo.paymentplatform.pg.mock.FakePgGatewayAdapterToss;
import com.hyoguoo.paymentplatform.pg.mock.FakePgInboxRepository;
import com.hyoguoo.paymentplatform.pg.mock.FakePgOutboxRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K14 RED: PgVendorCallService — vendorType 기반 strategy 분기 검증.
 *
 * <p>TOSS 요청 → Toss strategy 호출됨 (Nicepay는 호출되지 않음).
 * NICEPAY 요청 → Nicepay strategy 호출됨 (Toss는 호출되지 않음).
 */
@DisplayName("PgVendorCallService — vendorType 기반 전략 분기")
class PgVendorCallServiceVendorTypeTest {

    private static final String ORDER_ID_TOSS = "order-kt-toss-001";
    private static final String ORDER_ID_NICEPAY = "order-kt-nicepay-001";
    private static final String PAYMENT_KEY = "pk-kt-001";
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(10000);
    private static final Instant NOW = Instant.parse("2026-04-24T00:00:00Z");

    private FakePgGatewayAdapterToss tossAdapter;
    private FakePgGatewayAdapterNicepay nicepayAdapter;
    private FakePgInboxRepository inboxRepository;
    private FakePgOutboxRepository outboxRepository;
    private ApplicationEventPublisher eventPublisher;
    private PgVendorCallService sut;

    @BeforeEach
    void setUp() {
        tossAdapter = new FakePgGatewayAdapterToss();
        nicepayAdapter = new FakePgGatewayAdapterNicepay();
        inboxRepository = new FakePgInboxRepository();
        outboxRepository = new FakePgOutboxRepository();
        eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        ObjectMapper objectMapper = new ObjectMapper();
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);

        PgConfirmStrategySelector confirmSelector =
                new PgConfirmStrategySelector(List.of(tossAdapter, nicepayAdapter));

        sut = new PgVendorCallService(
                inboxRepository, outboxRepository, confirmSelector, eventPublisher,
                new ConfirmedEventPayloadSerializer(objectMapper), objectMapper, fixedClock);

        // inbox IN_PROGRESS 사전 준비
        inboxRepository.save(PgInbox.of(
                ORDER_ID_TOSS, PgInboxStatus.IN_PROGRESS, AMOUNT.longValue(),
                null, null, NOW, NOW));
        inboxRepository.save(PgInbox.of(
                ORDER_ID_NICEPAY, PgInboxStatus.IN_PROGRESS, AMOUNT.longValue(),
                null, null, NOW, NOW));
    }

    // -----------------------------------------------------------------------
    // TC1: TOSS 요청 → Toss strategy 호출됨
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("callVendor — vendorType=TOSS 이면 Toss strategy 호출됨")
    void callVendor_WhenToss_ShouldUseTossStrategy() {
        // given
        PgConfirmResult result = new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS, PAYMENT_KEY, ORDER_ID_TOSS, AMOUNT, null, null,
                "2026-04-24T00:00:00Z");
        tossAdapter.setConfirmResult(ORDER_ID_TOSS, result);

        // when
        sut.callVendor(new PgConfirmRequest(ORDER_ID_TOSS, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS), 1, NOW);

        // then — Toss strategy 호출됨, Nicepay 미호출
        assertThat(tossAdapter.getConfirmCallCount()).isEqualTo(1);
        assertThat(nicepayAdapter.getConfirmCallCount()).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // TC2: NICEPAY 요청 → Nicepay strategy 호출됨
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("callVendor — vendorType=NICEPAY 이면 Nicepay strategy 호출됨")
    void callVendor_WhenNicepay_ShouldUseNicepayStrategy() {
        // given
        PgConfirmResult result = new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS, PAYMENT_KEY, ORDER_ID_NICEPAY, AMOUNT, null, null,
                "2026-04-24T00:00:00Z");
        nicepayAdapter.setConfirmResult(ORDER_ID_NICEPAY, result);

        // when
        sut.callVendor(new PgConfirmRequest(ORDER_ID_NICEPAY, PAYMENT_KEY, AMOUNT, PgVendorType.NICEPAY), 1, NOW);

        // then — Nicepay strategy 호출됨, Toss 미호출
        assertThat(nicepayAdapter.getConfirmCallCount()).isEqualTo(1);
        assertThat(tossAdapter.getConfirmCallCount()).isEqualTo(0);
    }
}
