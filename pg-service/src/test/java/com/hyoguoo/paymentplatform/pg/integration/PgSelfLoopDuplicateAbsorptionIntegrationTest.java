package com.hyoguoo.paymentplatform.pg.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.event.ConfirmedEventPayloadSerializer;
import com.hyoguoo.paymentplatform.pg.application.event.DuplicateApprovalDetectedEvent;
import com.hyoguoo.paymentplatform.pg.application.service.DuplicateApprovalHandler;
import com.hyoguoo.paymentplatform.pg.application.service.PgConfirmStrategySelector;
import com.hyoguoo.paymentplatform.pg.application.service.PgInboxProcessor;
import com.hyoguoo.paymentplatform.pg.application.service.PgStatusLookupStrategySelector;
import com.hyoguoo.paymentplatform.pg.application.service.PgVendorCallService;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.mock.FakePgGatewayAdapter;
import com.hyoguoo.paymentplatform.pg.mock.FakePgInboxRepository;
import com.hyoguoo.paymentplatform.pg.mock.FakePgOutboxRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 재시도 자기루프(IN_PROGRESS self-loop) -> 벤더 재호출 -> 중복 승인 흡수(DuplicateApprovalHandler)
 * -> 최종 결제 종결 경로 통합 테스트.
 *
 * <p>test mock {@code FakePgGatewayAdapter} 의 상태 기반 멱등 모드({@code enableIdempotentDuplicate()})
 * 를 사용해, 좀비 폴링이 이미 SUCCESS 처리된 paymentKey 를 재호출하는 시나리오를 재현한다.
 *
 * <p>Spring 컨텍스트 없이 {@link PgInboxProcessor} + {@link PgVendorCallService} +
 * {@link DuplicateApprovalHandler} 를 Fake 저장소로 직접 wiring 한다 — production 의
 * {@code @EventListener} 동기 디스패치를 그대로 모사하기 위해, 테스트용
 * {@link ApplicationEventPublisher} 구현이 {@link DuplicateApprovalDetectedEvent} 발행을
 * {@link DuplicateApprovalHandler#onDuplicateApprovalDetected} 호출로 즉시 전달한다.
 *
 * <p>단언 기준값: 최종 pg_inbox 상태 = APPROVED(승인 종결), storedStatusResult 의 amount 가
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
    private FakePgOutboxRepository outboxRepository;
    private FakePgGatewayAdapter gatewayAdapter;
    private DuplicateApprovalHandler duplicateApprovalHandler;
    private PgInboxProcessor pgInboxProcessor;

    @BeforeEach
    void setUp() {
        inboxRepository = new FakePgInboxRepository();
        outboxRepository = new FakePgOutboxRepository();
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
        // 핸들러를 직접 호출하는 테스트용 ApplicationEventPublisher 를 구성한다(아래 buildEventPublisher).
        ApplicationEventPublisher[] publisherHolder = new ApplicationEventPublisher[1];
        duplicateApprovalHandler = new DuplicateApprovalHandler(
                statusLookupStrategySelector, inboxRepository, outboxRepository,
                event -> publisherHolder[0].publishEvent(event), payloadSerializer, FIXED_CLOCK);

        ApplicationEventPublisher dispatchingPublisher = event -> {
            if (event instanceof DuplicateApprovalDetectedEvent duplicateEvent) {
                duplicateApprovalHandler.onDuplicateApprovalDetected(duplicateEvent);
            }
            // PgOutboxReadyEvent 등 다른 이벤트는 이 통합 테스트 단언 범위 밖 — no-op.
        };
        publisherHolder[0] = dispatchingPublisher;

        PgVendorCallService vendorCallService = new PgVendorCallService(
                inboxRepository, outboxRepository, confirmStrategySelector,
                dispatchingPublisher, payloadSerializer, objectMapper, FIXED_CLOCK,
                duplicateApprovalHandler);

        pgInboxProcessor = new PgInboxProcessor(inboxRepository, vendorCallService, FIXED_CLOCK);
    }

    @Test
    @DisplayName("첫 confirm SUCCESS 후 동일 paymentKey self-loop 재호출 -> duplicate 흡수 -> 최종 APPROVED 종결, amount 1회분 유지")
    void 재시도_자기루프_중복승인_흡수_최종종결() {
        // given — 첫 confirm 은 happy-path SUCCESS, 이후 재호출은 멱등 모드로 duplicate 흡수.
        gatewayAdapter.enableIdempotentDuplicate();

        Long inboxId = inboxRepository.insertPending(
                ORDER_ID, AMOUNT.longValue(), "evt-uuid-1", "TOSS", PAYMENT_KEY, null);

        // when — 1차 처리: PENDING -> IN_PROGRESS -> 벤더 confirm SUCCESS -> APPROVED 종결.
        pgInboxProcessor.processPending(inboxId);

        PgInbox afterFirst = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(afterFirst.getStatus())
                .as("1차 처리 후 즉시 APPROVED 종결되어야 한다")
                .isEqualTo(PgInboxStatus.APPROVED);

        // 재시도 자기루프 재현을 위해 inbox 를 IN_PROGRESS 로 강제 환원한다 — 좀비 폴링이
        // 워커 크래시로 종결 반영 전에 잔존한 IN_PROGRESS row 를 재처리하는 상황을 모사한다.
        forceBackToInProgress();

        // when — self-loop 재호출: processInProgressZombie 가 동일 paymentKey 로 벤더를 재호출한다.
        // FakePgGatewayAdapter 멱등 모드는 이미 SUCCESS 처리된 paymentKey 재호출에
        // PgGatewayDuplicateHandledException 을 던지고, PgVendorCallService 가 이를
        // HandledInternally outcome 으로 변환해 DuplicateApprovalHandler 에 위임한다.
        pgInboxProcessor.processInProgressZombie(inboxId);

        // then — 최종 결제 상태는 다시 APPROVED(=DONE) 로 종결되어야 한다.
        PgInbox afterSelfLoop = inboxRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(afterSelfLoop.getStatus())
                .as("self-loop 중복 흡수 후에도 최종 결제 상태는 APPROVED(승인 종결)여야 한다")
                .isEqualTo(PgInboxStatus.APPROVED);

        // then — 중복 흡수 경로가 재발행한 storedStatusResult 의 amount 가 최초 confirm 과 동일해야
        // 한다(=이중 차감/금액 불일치로 인한 QUARANTINED 분기가 아니라 1회분 confirm 결과 그대로 흡수).
        assertThat(afterSelfLoop.getStoredStatusResult())
                .as("재고/정산 1회분만 반영되도록 storedStatusResult 의 amount 가 최초 confirm 과 일치해야 한다")
                .contains("\"amount\":" + AMOUNT.longValue());

        // then — 벤더 confirm 호출은 정확히 2회(최초 1회 + self-loop 재호출 1회) — 추가 차감을
        // 유발할 3차 호출은 없다.
        assertThat(gatewayAdapter.getConfirmCallCount())
                .as("벤더 confirm 호출은 최초 1회 + self-loop 재호출 1회, 총 2회여야 한다")
                .isEqualTo(2);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    /**
     * 1차 처리로 APPROVED 종결된 inbox 를 IN_PROGRESS 로 강제 환원한다.
     * 재시도 자기루프(워커 크래시로 종결 반영 전 IN_PROGRESS 잔존) 시나리오를 재현하기 위한
     * 테스트 전용 보조 — 운영 코드 경로가 아니라 FakePgInboxRepository 내부 상태를 직접 조작한다.
     */
    private void forceBackToInProgress() {
        Optional<PgInbox> current = inboxRepository.findByOrderId(ORDER_ID);
        PgInbox approved = current.orElseThrow();
        PgInbox reverted = PgInbox.of(
                ORDER_ID, PgInboxStatus.IN_PROGRESS, approved.getAmount(),
                null, null, approved.getCreatedAt(), Instant.now(),
                PAYMENT_KEY, PgVendorType.TOSS.name());
        inboxRepository.save(reverted);
    }
}
