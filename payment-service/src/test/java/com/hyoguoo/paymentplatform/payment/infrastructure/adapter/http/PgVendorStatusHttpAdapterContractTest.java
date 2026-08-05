package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgVendorStatusInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgVendorStatusJudgement;
import com.hyoguoo.paymentplatform.payment.exception.PgVendorStatusQueryFailedException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.PgVendorStatusResponse;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign.PgVendorStatusFeignClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PgVendorStatusHttpAdapter 계약 테스트 — PgAttemptHistoryHttpAdapterContractTest 패턴.
 *
 * <p>이 어댑터는 {@code PgAttemptHistoryHttpAdapter} 와 달리 어떤 예외도 밖으로 던지지 않는다 —
 * 통신 예외든 pg 자체 오류든 확인 불가로 접어 반환한다. 이 포트를 쓰는 격리 종결 판정이 승인/실패/
 * 확인불가 세 갈래 분기 하나로 끝나야 하기 때문이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PgVendorStatusHttpAdapter 계약 — 모든 실패가 확인 불가로 흡수된다")
class PgVendorStatusHttpAdapterContractTest {

    private static final String ORDER_ID = "order-1";
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-06T00:00:00Z");

    @Mock
    private PgVendorStatusFeignClient pgVendorStatusFeignClient;

    private PgVendorStatusHttpAdapter adapter;

    @Test
    @DisplayName("정상 응답 → 판정/벤더 원 상태/조회 시각 세 값이 모두 도메인 값으로 매핑된다")
    void lookup_WhenSuccess_ShouldMapAllThreeValues() {
        adapter = new PgVendorStatusHttpAdapter(pgVendorStatusFeignClient, Clock.systemUTC());
        Instant queriedAt = Instant.parse("2026-08-06T00:00:05Z");
        PgVendorStatusResponse response =
                new PgVendorStatusResponse(ORDER_ID, "APPROVED", "DONE", queriedAt);
        given(pgVendorStatusFeignClient.getVendorStatus(ORDER_ID)).willReturn(response);

        PgVendorStatusInfo result = adapter.lookup(ORDER_ID);

        assertThat(result.judgement()).isEqualTo(PgVendorStatusJudgement.APPROVED);
        assertThat(result.vendorStatus()).isEqualTo("DONE");
        assertThat(result.queriedAt()).isEqualTo(queriedAt);
    }

    @Test
    @DisplayName("통신 예외(feign.RetryableException)면 확인불가를 반환한다")
    void lookup_WhenTransportExceptionThrown_ShouldReturnUnknown() {
        adapter = new PgVendorStatusHttpAdapter(pgVendorStatusFeignClient, fixedClock());
        feign.RetryableException transportException = org.mockito.Mockito.mock(feign.RetryableException.class);
        given(pgVendorStatusFeignClient.getVendorStatus(ORDER_ID)).willThrow(transportException);

        PgVendorStatusInfo result = adapter.lookup(ORDER_ID);

        assertThat(result.judgement()).isEqualTo(PgVendorStatusJudgement.UNKNOWN);
        assertThat(result.queriedAt()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    @DisplayName("pg 자체 오류(PgVendorStatusQueryFailedException)면 확인불가를 반환한다")
    void lookup_WhenServiceExceptionThrown_ShouldReturnUnknown() {
        adapter = new PgVendorStatusHttpAdapter(pgVendorStatusFeignClient, fixedClock());
        given(pgVendorStatusFeignClient.getVendorStatus(ORDER_ID))
                .willThrow(PgVendorStatusQueryFailedException.of(PaymentErrorCode.PG_VENDOR_STATUS_QUERY_UNAVAILABLE));

        PgVendorStatusInfo result = adapter.lookup(ORDER_ID);

        assertThat(result.judgement()).isEqualTo(PgVendorStatusJudgement.UNKNOWN);
        assertThat(result.queriedAt()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    @DisplayName("어댑터는 어떤 경우에도 예외를 던지지 않는다")
    void lookup_NeverThrows() {
        adapter = new PgVendorStatusHttpAdapter(pgVendorStatusFeignClient, fixedClock());
        given(pgVendorStatusFeignClient.getVendorStatus(ORDER_ID))
                .willThrow(new IllegalStateException("boom"));

        assertThatCode(() -> adapter.lookup(ORDER_ID)).doesNotThrowAnyException();
    }

    private Clock fixedClock() {
        return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    }
}
