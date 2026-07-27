package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgAttemptHistoryInfo;
import com.hyoguoo.paymentplatform.payment.exception.PgAttemptHistoryNotFoundException;
import com.hyoguoo.paymentplatform.payment.exception.PgAttemptHistoryServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.PgAttemptEntryResponse;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.dto.PgAttemptHistoryResponse;
import com.hyoguoo.paymentplatform.payment.infrastructure.adapter.http.feign.PgFeignClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PgAttemptHistoryHttpAdapter 계약 테스트 — ProductHttpAdapterContractTest 패턴.
 *
 * <p>4xx/5xx → 도메인 예외 매핑 자체는 PgFeignConfigTest 에서 별도 검증한다. 여기서는
 * FeignClient 가 이미 도메인 예외를 던진 경우 어댑터가 그대로 propagate 하는지,
 * transport-level 예외를 변환하는지, 정상 응답을 도메인 DTO 로 옮기는지를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PgAttemptHistoryHttpAdapter 계약 — FeignClient 예외 propagation / 응답 변환")
class PgAttemptHistoryHttpAdapterContractTest {

    @Mock
    private PgFeignClient pgFeignClient;

    @InjectMocks
    private PgAttemptHistoryHttpAdapter adapter;

    @Test
    @DisplayName("FeignClient 가 도메인 예외(PgAttemptHistoryNotFoundException) throw → 어댑터가 그대로 propagate")
    void getAttemptHistory_WhenFeignThrowsDomainException_ShouldPropagate() {
        given(pgFeignClient.getAttemptHistory("order-1"))
                .willThrow(PgAttemptHistoryNotFoundException.of(PaymentErrorCode.PG_ATTEMPT_HISTORY_NOT_FOUND));

        assertThatThrownBy(() -> adapter.getAttemptHistory("order-1"))
                .isInstanceOf(PgAttemptHistoryNotFoundException.class);
    }

    @Test
    @DisplayName("FeignClient 가 feign.RetryableException(transport 오류) throw → 어댑터가 PgAttemptHistoryServiceRetryableException 으로 변환")
    void getAttemptHistory_WhenFeignThrowsTransportRetryableException_ShouldConvertToRetryable() {
        feign.RetryableException transportException = mock(feign.RetryableException.class);
        given(pgFeignClient.getAttemptHistory("order-1")).willThrow(transportException);

        assertThatThrownBy(() -> adapter.getAttemptHistory("order-1"))
                .isInstanceOf(PgAttemptHistoryServiceRetryableException.class);
    }

    @Test
    @DisplayName("정상 응답 → 도메인 DTO 변환 (회차·세 시각·정상 시도 여부 누락 없이)")
    void getAttemptHistory_WhenSuccess_ShouldConvertToDomainDto() {
        Instant reservedAt = Instant.parse("2026-07-27T00:00:00Z");
        Instant scheduledAt = Instant.parse("2026-07-27T00:00:05Z");
        Instant publishedAt = Instant.parse("2026-07-27T00:00:06Z");
        Instant finalizedAt = Instant.parse("2026-07-27T00:00:10Z");
        PgAttemptEntryResponse entryResponse = new PgAttemptEntryResponse(
                2, reservedAt, scheduledAt, publishedAt, false, true
        );
        PgAttemptHistoryResponse response = new PgAttemptHistoryResponse(
                "order-1", true, "APPROVED", finalizedAt, null, List.of(entryResponse)
        );
        given(pgFeignClient.getAttemptHistory("order-1")).willReturn(response);

        PgAttemptHistoryInfo result = adapter.getAttemptHistory("order-1");

        assertThat(result.getOrderId()).isEqualTo("order-1");
        assertThat(result.isFound()).isTrue();
        assertThat(result.getFinalStatus()).isEqualTo("APPROVED");
        assertThat(result.getFinalizedAt()).isEqualTo(finalizedAt);
        assertThat(result.getReasonCode()).isNull();
        assertThat(result.getAttempts()).hasSize(1);
        assertThat(result.getAttempts().get(0).getAttemptNo()).contains(2);
        assertThat(result.getAttempts().get(0).getReservedAt()).isEqualTo(reservedAt);
        assertThat(result.getAttempts().get(0).getScheduledAt()).isEqualTo(scheduledAt);
        assertThat(result.getAttempts().get(0).getPublishedAt()).isEqualTo(publishedAt);
        assertThat(result.getAttempts().get(0).isExhausted()).isFalse();
        assertThat(result.getAttempts().get(0).isNormalAttempt()).isTrue();
    }
}
