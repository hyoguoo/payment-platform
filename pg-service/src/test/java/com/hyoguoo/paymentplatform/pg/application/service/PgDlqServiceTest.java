package com.hyoguoo.paymentplatform.pg.application.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmCommand;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * PgDlqService 단위 테스트 — 대기열 소비가 격리 직행 대신 관문({@link PgFinalConfirmationGate})
 * 호출로 위임되는지 검증한다.
 *
 * <p>전이·발행 상세 동작은 관문 자체 책임이라 {@link PgFinalConfirmationGateTest} 가 커버한다.
 * 이 테스트는 위임 여부(관문 호출 횟수·인자, 전위 조회가 잠금을 쓰지 않는지)만 확인한다.
 * domain_risk=true — 이미 종결된 기록에 관문을 다시 부르면 벤더 재조회가 중복 발생한다.
 */
@DisplayName("PgDlqService — 관문 위임")
class PgDlqServiceTest {

    private static final String ORDER_ID = "order-dlq-001";
    private static final String EVENT_UUID = "uuid-dlq-001";
    private static final long AMOUNT = 15000L;

    private PgInboxRepository pgInboxRepository;
    private PgFinalConfirmationGate pgFinalConfirmationGate;
    private PgDlqService sut;

    @BeforeEach
    void setUp() {
        pgInboxRepository = mock(PgInboxRepository.class);
        pgFinalConfirmationGate = mock(PgFinalConfirmationGate.class);
        sut = new PgDlqService(pgInboxRepository, pgFinalConfirmationGate);
    }

    @Test
    @DisplayName("대기열소비_접수기록이_없으면_아무것도_하지_않는다")
    void 대기열소비_접수기록이_없으면_아무것도_하지_않는다() {
        // given
        given(pgInboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());

        // when
        sut.handle(buildCommand());

        // then
        then(pgFinalConfirmationGate).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @EnumSource(value = PgInboxStatus.class, names = {"APPROVED", "FAILED", "QUARANTINED"})
    @DisplayName("대기열소비_이미_종결이면_관문을_부르지_않는다")
    void 대기열소비_이미_종결이면_관문을_부르지_않는다(PgInboxStatus terminalStatus) {
        // given
        given(pgInboxRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(buildInbox(terminalStatus)));

        // when
        sut.handle(buildCommand());

        // then
        then(pgFinalConfirmationGate).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @EnumSource(value = PgInboxStatus.class, names = {"PENDING", "IN_PROGRESS"})
    @DisplayName("대기열소비_종결전이면_관문을_부른다 — 관문 호출 1회")
    void 대기열소비_종결전이면_관문을_부른다(PgInboxStatus nonTerminalStatus) {
        // given
        given(pgInboxRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(buildInbox(nonTerminalStatus)));
        PgFinalConfirmationGate.FcgOutcome outcome =
                new PgFinalConfirmationGate.FcgOutcome.Quarantined("FCG_INDETERMINATE");
        given(pgFinalConfirmationGate.invokeVendor(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS))
                .willReturn(outcome);

        // when
        sut.handle(buildCommand());

        // then — 조회(invokeVendor)와 반영(applyOutcome)이 정확히 1회씩, 이 순서로 위임된다
        then(pgFinalConfirmationGate).should(times(1))
                .invokeVendor(ORDER_ID, EVENT_UUID, AMOUNT, PgVendorType.TOSS);
        then(pgFinalConfirmationGate).should(times(1)).applyOutcome(outcome, ORDER_ID, AMOUNT);
    }

    @Test
    @DisplayName("대기열소비_전위조회에_잠금을_쓰지_않는다")
    void 대기열소비_전위조회에_잠금을_쓰지_않는다() {
        // given
        given(pgInboxRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(buildInbox(PgInboxStatus.IN_PROGRESS)));
        given(pgFinalConfirmationGate.invokeVendor(any(), any(), anyLong(), any()))
                .willReturn(new PgFinalConfirmationGate.FcgOutcome.Quarantined("FCG_INDETERMINATE"));

        // when
        sut.handle(buildCommand());

        // then — 잠금 조회(findByOrderIdForUpdate)를 쓰지 않는다. 원자성은 관문 반영 단계가 맡는다.
        then(pgInboxRepository).should(times(1)).findByOrderId(ORDER_ID);
        then(pgInboxRepository).should(never()).findByOrderIdForUpdate(any());
    }

    private PgInbox buildInbox(PgInboxStatus status) {
        return PgInbox.of(ORDER_ID, status, AMOUNT, null, null, Instant.now(), Instant.now());
    }

    private PgConfirmCommand buildCommand() {
        return new PgConfirmCommand(
                ORDER_ID, "pk-dlq-001", BigDecimal.valueOf(AMOUNT), PgVendorType.TOSS, EVENT_UUID);
    }
}
