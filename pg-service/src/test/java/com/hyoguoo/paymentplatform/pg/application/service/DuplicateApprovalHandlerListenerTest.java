package com.hyoguoo.paymentplatform.pg.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayloadSerializer;
import com.hyoguoo.paymentplatform.pg.application.event.DuplicateApprovalDetectedEvent;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.mock.FakePgGatewayAdapter;
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
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * K13: DuplicateApprovalHandler — DuplicateApprovalDetectedEvent 수신 시 handleDuplicateApproval 위임 단위 테스트.
 *
 * <p>불변식:
 * <ul>
 *   <li>TC1: DuplicateApprovalHandler 에 onDuplicateApprovalDetected(DuplicateApprovalDetectedEvent) 메서드가 존재해야 함</li>
 *   <li>TC2: 이벤트 수신 → handleDuplicateApproval 호출 (vendor 조회 → outbox 1건 생성)</li>
 * </ul>
 */
@DisplayName("DuplicateApprovalHandler K13 — EventListener 위임")
class DuplicateApprovalHandlerListenerTest {

    private static final String ORDER_ID = "order-listener-001";
    private static final BigDecimal PAYLOAD_AMOUNT = BigDecimal.valueOf(10000L);
    private static final long AMOUNT_LONG = 10000L;

    private FakePgGatewayAdapter gatewayAdapter;
    private FakePgInboxRepository inboxRepository;
    private FakePgOutboxRepository outboxRepository;
    private ApplicationEventPublisher eventPublisher;
    private DuplicateApprovalHandler handler;

    @BeforeEach
    void setUp() {
        gatewayAdapter = new FakePgGatewayAdapter();
        inboxRepository = new FakePgInboxRepository();
        outboxRepository = new FakePgOutboxRepository();
        eventPublisher = mock(ApplicationEventPublisher.class);
        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-24T01:00:00Z"), ZoneOffset.UTC);
        // K14: FakePgGatewayAdapter.supports(vendorType)=true(모든 벤더) → selector가 항상 반환함
        PgStatusLookupStrategySelector selector = new PgStatusLookupStrategySelector(List.of(gatewayAdapter));
        handler = new DuplicateApprovalHandler(
                selector, inboxRepository, outboxRepository, eventPublisher,
                new ConfirmedEventPayloadSerializer(new ObjectMapper()), fixedClock);
    }

    /**
     * TC1: DuplicateApprovalHandler 에 onDuplicateApprovalDetected 메서드가 존재해야 한다.
     *
     * <p>K13: @EventListener 또는 @TransactionalEventListener 기반 리스너 메서드 신설.
     */
    @Test
    @DisplayName("onDuplicateApprovalDetected_메서드_존재해야_한다")
    void onDuplicateApprovalDetected_메서드_존재해야_한다() {
        boolean methodExists;
        try {
            DuplicateApprovalHandler.class.getMethod(
                    "onDuplicateApprovalDetected",
                    DuplicateApprovalDetectedEvent.class
            );
            methodExists = true;
        } catch (NoSuchMethodException e) {
            methodExists = false;
        }

        assertThat(methodExists)
                .as("DuplicateApprovalHandler 에 onDuplicateApprovalDetected(DuplicateApprovalDetectedEvent) 메서드가 있어야 함 — K13 EventListener")
                .isTrue();
    }

    /**
     * TC2: DuplicateApprovalDetectedEvent 수신 시 handleDuplicateApproval 동등 결과 — outbox 1건 생성.
     *
     * <p>K13: 이벤트 수신 → handleDuplicateApproval(orderId, amount) 호출 위임.
     * VENDOR_INDETERMINATE 경로(상태 조회 예외 주입)로 outbox 1건 생성 여부만 검증.
     * reflection 으로 메서드 호출 — 메서드 부재 시 NoSuchMethodException 발생.
     */
    @Test
    @DisplayName("이벤트_수신시_handleDuplicateApproval_호출_위임_outbox_생성됨")
    void 이벤트_수신시_handleDuplicateApproval_호출_위임_outbox_생성됨() throws Exception {
        // given — vendor 조회 실패 주입 → VENDOR_INDETERMINATE 경로 → outbox 1건
        gatewayAdapter.throwOnStatusQuery(
                com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException.of("timeout"));

        // K14: vendorType 추가 — selector 분기에 사용
        DuplicateApprovalDetectedEvent event = new DuplicateApprovalDetectedEvent(
                ORDER_ID, PAYLOAD_AMOUNT, "pk-test-001", "ALREADY_PROCESSED_PAYMENT", PgVendorType.TOSS);

        // when — reflection 으로 이벤트 리스너 메서드 직접 호출 (Spring event bus 우회, 단위 테스트)
        java.lang.reflect.Method listenerMethod = DuplicateApprovalHandler.class.getMethod(
                "onDuplicateApprovalDetected",
                DuplicateApprovalDetectedEvent.class
        );
        listenerMethod.invoke(handler, event);

        // then — outbox 1건 생성 = handleDuplicateApproval 호출이 완료됐음을 의미
        assertThat(outboxRepository.findAll()).hasSize(1);
    }
}
