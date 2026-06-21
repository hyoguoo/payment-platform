package com.hyoguoo.paymentplatform.pg.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayloadSerializer;
import com.hyoguoo.paymentplatform.pg.application.event.DuplicateApprovalDetectedEvent;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgConfirmResultStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.mock.FakePgGatewayAdapter;
import com.hyoguoo.paymentplatform.pg.mock.FakePgInboxRepository;
import com.hyoguoo.paymentplatform.pg.mock.FakePgOutboxRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 재시도 자기루프(동일 paymentKey 벤더 재호출) -> 중복 승인 흡수(DuplicateApprovalHandler)
 * -> 최종 결제 종결 경로 통합 테스트.
 *
 * <p>test mock {@code FakePgGatewayAdapter} 의 상태 기반 멱등 모드({@code enableIdempotentDuplicate()})
 * 를 사용해, 이미 SUCCESS 처리된 paymentKey 로 벤더가 재호출되는 self-loop 시나리오를 재현한다.
 * 1차 호출은 {@link PgInboxProcessor#processPending} 정상 경로로 APPROVED 종결시키고,
 * self-loop 재호출은 {@link PgVendorCallService#invokeVendor}/{@code applyOutcome} 을 동일
 * paymentKey 로 한 번 더 호출해 재현한다 — production 에서는 워커 좀비/동시 처리 경합으로
 * 동일 IN_PROGRESS row 에 대해 벤더가 두 번째로 호출될 때 발생하는 경로다.
 *
 * <p>이 테스트는 같은 패키지({@code pg.application.service}) 에 둔다 — package-private sealed
 * interface {@code GatewayOutcome} 을 직접 다루지 않고 {@link PgVendorCallService} 의
 * public API(invokeVendor/applyOutcome) 만 사용하지만, {@link PgInboxProcessor} 와 동일하게
 * production 서비스 객체를 직접 구성하는 Fake-wiring 패턴을 따른다.
 *
 * <p>Spring 컨텍스트 없이 {@link PgInboxProcessor} + {@link PgVendorCallService} +
 * {@link DuplicateApprovalHandler} 를 Fake 저장소로 직접 wiring 한다 — production 의
 * {@code @EventListener} 동기 디스패치를 그대로 모사하기 위해, 테스트용
 * {@link ApplicationEventPublisher} 구현이 {@link DuplicateApprovalDetectedEvent} 발행을
 * {@link DuplicateApprovalHandler#onDuplicateApprovalDetected} 호출로 즉시 전달한다.
 * {@code FakePgGatewayAdapter} 에도 동일 publisher 를 연결해, 실 벤더(TossPaymentGatewayStrategy)와
 * 동일하게 멱등 모드 duplicate 흡수 시 이벤트 발행 후 예외 throw 가 이뤄지도록 한다 — 그 결과
 * {@code @EventListener} 경로와 {@link PgVendorCallService#applyOutcome} 의
 * catch(PgGatewayDuplicateHandledException) → handleDuplicate 직접 호출 경로가 함께 실행된다.
 *
 * <p>단언 기준값: 최종 pg_inbox 상태 = APPROVED(승인 종결) 유지, storedStatusResult 의 amount 가
 * 최초 confirm 과 동일(=재흡수로 인한 amount 불일치/이중 차감 없음). pg_outbox row 카운트에는
 * 의존하지 않는다(흡수 핸들러가 이벤트+예외 이중 경로로 2건 INSERT 가능).
 */
@Tag("integration")
@DisplayName("재시도 자기루프 중복 승인 흡수 통합 테스트")
class PgSelfLoopDuplicateAbsorptionIntegrationTest {

    private static final String ORDER_ID = "order-selfloop-001";
    private static final String PAYMENT_KEY = "pk-selfloop-001";
    private static final BigDecimal AMOUNT = BigDecimal.valueOf(15_000L);
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private FakePgInboxRepository inboxRepository;
    private FakePgGatewayAdapter gatewayAdapter;
    private PgVendorCallService vendorCallService;
    private PgInboxProcessor pgInboxProcessor;

    @BeforeEach
    void setUp() {
        inboxRepository = new FakePgInboxRepository();
        FakePgOutboxRepository outboxRepository = new FakePgOutboxRepository();
        gatewayAdapter = new FakePgGatewayAdapter();

        ObjectMapper objectMapper = new ObjectMapper();
        ConfirmedEventPayloadSerializer payloadSerializer =
                new ConfirmedEventPayloadSerializer(objectMapper);

        PgConfirmStrategySelector confirmStrategySelector =
                new PgConfirmStrategySelector(List.of(gatewayAdapter));
        PgStatusLookupStrategySelector statusLookupStrategySelector =
                new PgStatusLookupStrategySelector(List.of(gatewayAdapter));

        // DuplicateApprovalHandler 는 production 에서 @EventListener 로 DuplicateApprovalDetectedEvent
        // 를 수신한다. Spring 컨텍스트 없이도 동일 동기 디스패치를 모사하기 위해, 이벤트 발행 시
        // 핸들러를 직접 호출하는 테스트용 ApplicationEventPublisher 를 구성한다.
        ApplicationEventPublisher[] publisherHolder = new ApplicationEventPublisher[1];
        DuplicateApprovalHandler duplicateApprovalHandler = new DuplicateApprovalHandler(
                statusLookupStrategySelector, inboxRepository, outboxRepository,
                event -> publisherHolder[0].publishEvent(event), payloadSerializer, FIXED_CLOCK);

        ApplicationEventPublisher dispatchingPublisher = event -> {
            if (event instanceof DuplicateApprovalDetectedEvent duplicateEvent) {
                duplicateApprovalHandler.onDuplicateApprovalDetected(duplicateEvent);
            }
            // PgOutboxReadyEvent 등 다른 이벤트는 이 통합 테스트 단언 범위 밖 — no-op.
        };
        publisherHolder[0] = dispatchingPublisher;

        vendorCallService = new PgVendorCallService(
                inboxRepository, outboxRepository, confirmStrategySelector,
                dispatchingPublisher, payloadSerializer, objectMapper, FIXED_CLOCK,
                duplicateApprovalHandler);

        pgInboxProcessor = new PgInboxProcessor(inboxRepository, vendorCallService, FIXED_CLOCK);

        // 실 벤더(TossPaymentGatewayStrategy)와 동일하게 멱등 모드 duplicate 흡수 시
        // DuplicateApprovalDetectedEvent 를 발행하도록 wiring — onDuplicateApprovalDetected
        // (@EventListener) 경로도 함께 태운다. invokeConfirm 의 catch(PgGatewayDuplicateHandledException)
        // → handleDuplicate 직접 호출 경로와 합쳐 이벤트+예외 이중 경로가 된다(실 벤더와 동일).
        gatewayAdapter.setApplicationEventPublisher(dispatchingPublisher);
    }

    @Test
    @DisplayName("첫 confirm SUCCESS 후 동일 paymentKey self-loop 재호출 -> duplicate 흡수 -> 최종 APPROVED 종결, amount 1회분 유지")
    void 재시도_자기루프_중복승인_흡수_최종종결() {
        // given — 첫 confirm 은 happy-path SUCCESS, 이후 재호출은 멱등 모드로 duplicate 흡수.
        gatewayAdapter.enableIdempotentDuplicate();
        gatewayAdapter.setConfirmResult(ORDER_ID, new PgConfirmResult(
                PgConfirmResultStatus.SUCCESS, PAYMENT_KEY, ORDER_ID, AMOUNT,
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), null, "2026-06-21T00:00:00"));

        Long inboxId = inboxRepository.insertPending(
                ORDER_ID, AMOUNT.longValue(), "evt-uuid-1", "TOSS", PAYMENT_KEY, null);

        // when — 1차 처리: PENDING -> IN_PROGRESS -> 벤더 confirm SUCCESS -> APPROVED 종결.
        pgInboxProcessor.processPending(inboxId);

        PgInbox afterFirst = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(afterFirst.getStatus())
                .as("1차 처리 후 즉시 APPROVED 종결되어야 한다")
                .isEqualTo(PgInboxStatus.APPROVED);
        String firstStoredStatusResult = afterFirst.getStoredStatusResult();

        // when — self-loop 재호출: 동일 paymentKey 로 벤더를 한 번 더 호출한다(워커 좀비/동시 처리
        // 경합으로 같은 row 에 대해 벤더가 두 번째로 불리는 상황). FakePgGatewayAdapter 멱등 모드는
        // 실 벤더와 동일하게 DuplicateApprovalDetectedEvent 발행(→ @EventListener 경로) 후
        // PgGatewayDuplicateHandledException 을 던지고, PgVendorCallService 가 이를
        // HandledInternally outcome 으로 변환해 DuplicateApprovalHandler 에 직접 위임한다(이중 경로).
        PgConfirmRequest selfLoopRequest =
                new PgConfirmRequest(ORDER_ID, PAYMENT_KEY, AMOUNT, PgVendorType.TOSS);
        applySelfLoopReinvocation(selfLoopRequest);

        // then — 중복 흡수 경로는 이미 terminal 인 inbox 상태를 변경하지 않는다 — 여전히 APPROVED.
        PgInbox afterSelfLoop = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(afterSelfLoop.getStatus())
                .as("self-loop 중복 흡수 후에도 최종 결제 상태는 APPROVED(승인 종결)로 유지되어야 한다")
                .isEqualTo(PgInboxStatus.APPROVED);

        // then — 중복 흡수 경로가 재발행한 storedStatusResult 는 최초 confirm 결과 그대로다(=이중
        // 차감/금액 불일치로 인한 QUARANTINED 분기가 아니라 1회분 confirm 결과 그대로 흡수).
        assertThat(afterSelfLoop.getStoredStatusResult())
                .as("재고/정산 1회분만 반영되도록 storedStatusResult 가 최초 confirm 결과와 동일해야 한다")
                .isEqualTo(firstStoredStatusResult)
                .contains("\"amount\":" + AMOUNT.longValue());

        // then — 벤더 confirm 호출은 정확히 2회(최초 1회 + self-loop 재호출 1회) — 추가 차감을
        // 유발할 3차 호출은 없다.
        assertThat(gatewayAdapter.getConfirmCallCount())
                .as("벤더 confirm 호출은 최초 1회 + self-loop 재호출 1회, 총 2회여야 한다")
                .isEqualTo(2);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    /**
     * self-loop 재호출(동일 paymentKey 벤더 재호출 -> duplicate 흡수)을 production 과 동일한
     * TX 분리 시퀀스(invokeVendor TX 외부 -> applyOutcome TX_B)로 적용한다.
     */
    private void applySelfLoopReinvocation(PgConfirmRequest request) {
        vendorCallService.applyOutcome(
                vendorCallService.invokeVendor(request), request, 1, FIXED_CLOCK.instant());
    }
}
