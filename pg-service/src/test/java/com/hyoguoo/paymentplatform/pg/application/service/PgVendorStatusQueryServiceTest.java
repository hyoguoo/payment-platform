package com.hyoguoo.paymentplatform.pg.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgVendorStatusJudgement;
import com.hyoguoo.paymentplatform.pg.application.dto.PgVendorStatusView;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgInboxRepository;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgStatusLookupPort;
import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * PgVendorStatusQueryServiceImpl 단위 테스트.
 *
 * <p>격리 종결 판단 전 관리자가 누르는 벤더 상태 조회 — 벤더 원 상태를 승인/실패/확인불가 세 값으로
 * 접는 판정과, 조회 실패(예외 포함)가 5xx 가 아니라 확인불가로 흡수됨을 검증한다.
 * domain_risk=true — 게이트웨이 예외 두 종만 잡으면 모의 벤더의 미처리 주문 예외가 새어나가는
 * 회귀를 이 테스트가 고정한다.
 */
@DisplayName("PgVendorStatusQueryServiceImpl")
class PgVendorStatusQueryServiceTest {

    private static final String ORDER_ID = "order-vendor-status-001";

    private PgInboxRepository pgInboxRepository;
    private PgStatusLookupPort pgStatusLookupPort;
    private PgVendorStatusQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        pgInboxRepository = mock(PgInboxRepository.class);
        pgStatusLookupPort = mock(PgStatusLookupPort.class);
        given(pgStatusLookupPort.supports(any())).willReturn(true);

        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC);
        PgStatusLookupStrategySelector selector = new PgStatusLookupStrategySelector(List.of(pgStatusLookupPort));
        service = new PgVendorStatusQueryServiceImpl(pgInboxRepository, selector, fixedClock);

        given(pgInboxRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(existingInbox()));
    }

    private PgInbox existingInbox() {
        return PgInbox.of(
                ORDER_ID, PgInboxStatus.QUARANTINED, 1000L,
                null, "RETRY_EXHAUSTED", Instant.now(), Instant.now(),
                "pk-001", PgVendorType.TOSS.name());
    }

    private PgStatusResult statusResult(PgPaymentStatus status) {
        return new PgStatusResult("pk-001", ORDER_ID, status, BigDecimal.valueOf(1000L), null, null, null);
    }

    @Test
    @DisplayName("벤더 조회 결과가 DONE 이면 승인됨을 반환한다.")
    void 승인_상태면_승인됨을_반환한다() {
        // given
        given(pgStatusLookupPort.getStatusByOrderId(ORDER_ID)).willReturn(statusResult(PgPaymentStatus.DONE));

        // when
        PgVendorStatusView result = service.getVendorStatus(ORDER_ID);

        // then
        assertThat(result.judgement()).isEqualTo(PgVendorStatusJudgement.APPROVED);
        assertThat(result.vendorStatus()).isEqualTo("DONE");
    }

    @ParameterizedTest
    @EnumSource(value = PgPaymentStatus.class, names = {"CANCELED", "PARTIAL_CANCELED", "ABORTED", "EXPIRED"})
    @DisplayName("벤더 조회 결과가 확정 실패 상태면 실패됨을 반환한다.")
    void 실패_상태면_실패됨을_반환한다(PgPaymentStatus status) {
        // given
        given(pgStatusLookupPort.getStatusByOrderId(ORDER_ID)).willReturn(statusResult(status));

        // when
        PgVendorStatusView result = service.getVendorStatus(ORDER_ID);

        // then
        assertThat(result.judgement()).isEqualTo(PgVendorStatusJudgement.FAILED);
        assertThat(result.vendorStatus()).isEqualTo(status.name());
    }

    @ParameterizedTest
    @EnumSource(value = PgPaymentStatus.class, names = {"READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT"})
    @DisplayName("벤더 조회 결과가 미확정 상태면 확인불가를 반환한다.")
    void 미확정_상태면_확인불가를_반환한다(PgPaymentStatus status) {
        // given
        given(pgStatusLookupPort.getStatusByOrderId(ORDER_ID)).willReturn(statusResult(status));

        // when
        PgVendorStatusView result = service.getVendorStatus(ORDER_ID);

        // then
        assertThat(result.judgement()).isEqualTo(PgVendorStatusJudgement.UNKNOWN);
        assertThat(result.vendorStatus()).isEqualTo(status.name());
    }

    @Test
    @DisplayName("벤더 조회가 재시도 가능 예외를 던지면 확인불가를 반환한다.")
    void 벤더_조회가_예외면_확인불가를_반환한다_재시도가능() {
        // given
        given(pgStatusLookupPort.getStatusByOrderId(ORDER_ID))
                .willThrow(PgGatewayRetryableException.of("vendor timeout"));

        // when
        PgVendorStatusView result = service.getVendorStatus(ORDER_ID);

        // then
        assertThat(result.judgement()).isEqualTo(PgVendorStatusJudgement.UNKNOWN);
    }

    @Test
    @DisplayName("벤더 조회가 재시도 불가 예외를 던지면 확인불가를 반환한다.")
    void 벤더_조회가_예외면_확인불가를_반환한다_재시도불가() {
        // given
        given(pgStatusLookupPort.getStatusByOrderId(ORDER_ID))
                .willThrow(PgGatewayNonRetryableException.of("vendor rejected"));

        // when
        PgVendorStatusView result = service.getVendorStatus(ORDER_ID);

        // then
        assertThat(result.judgement()).isEqualTo(PgVendorStatusJudgement.UNKNOWN);
    }

    @Test
    @DisplayName("모의 벤더가 처리 기록 없는 주문 조회에 던지는 예외도 확인불가로 접는다 — 재시도 소진 격리 주문이 이 경우다.")
    void 모의_벤더의_미처리_주문_예외도_확인불가로_접는다() {
        // given
        given(pgStatusLookupPort.getStatusByOrderId(ORDER_ID))
                .willThrow(new UnsupportedOperationException("처리 기록 없는 orderId"));

        // when
        PgVendorStatusView result = service.getVendorStatus(ORDER_ID);

        // then
        assertThat(result.judgement()).isEqualTo(PgVendorStatusJudgement.UNKNOWN);
    }

    @Test
    @DisplayName("벤더 조회는 요청당 한 번만 호출된다.")
    void 벤더_조회는_한_번만_호출된다() {
        // given
        given(pgStatusLookupPort.getStatusByOrderId(ORDER_ID)).willReturn(statusResult(PgPaymentStatus.DONE));

        // when
        service.getVendorStatus(ORDER_ID);

        // then
        then(pgStatusLookupPort).should(times(1)).getStatusByOrderId(ORDER_ID);
    }

    @Test
    @DisplayName("pg_inbox 에 주문 기록이 없으면 확인불가를 반환하고 벤더는 부르지 않는다.")
    void 주문_기록이_없으면_확인불가를_반환한다() {
        // given
        given(pgInboxRepository.findByOrderId("order-missing")).willReturn(Optional.empty());

        // when
        PgVendorStatusView result = service.getVendorStatus("order-missing");

        // then
        assertThat(result.judgement()).isEqualTo(PgVendorStatusJudgement.UNKNOWN);
        then(pgStatusLookupPort).should(never()).getStatusByOrderId(any());
    }
}
