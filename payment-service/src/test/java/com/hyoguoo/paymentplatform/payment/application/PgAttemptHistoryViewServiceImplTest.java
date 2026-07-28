package com.hyoguoo.paymentplatform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgAttemptHistoryInfo;
import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgAttemptHistoryLookupResult;
import com.hyoguoo.paymentplatform.payment.application.port.out.PgAttemptHistoryPort;
import com.hyoguoo.paymentplatform.payment.exception.PgAttemptHistoryServiceRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * {@code PgAttemptHistoryViewServiceImpl} 단위 테스트.
 *
 * <p>조회 실패 흡수 + 조회 불가 폴백 판단(리뷰 finding 1로 컨트롤러에서 이 계층으로 옮김)이
 * 정확히 여기서 이뤄지는지 검증한다 — 도메인 예외/타임아웃 예외 어느 쪽이든 조회 불가로
 * 흡수돼야 한다(finding 3: try 범위를 포트 호출로 좁혔으므로 뷰 변환은 여기서 하지 않는다).
 */
class PgAttemptHistoryViewServiceImplTest {

    private static final String ORDER_ID = "order-1";

    private PgAttemptHistoryPort pgAttemptHistoryPort;
    private PgAttemptHistoryViewServiceImpl sut;

    @BeforeEach
    void setUp() {
        pgAttemptHistoryPort = Mockito.mock(PgAttemptHistoryPort.class);
        sut = new PgAttemptHistoryViewServiceImpl(pgAttemptHistoryPort);
    }

    @Test
    @DisplayName("포트 조회가 성공하면 조회 가능 결과에 그 값을 그대로 담는다")
    void getAttemptHistory_success_returnsAvailableResult() {
        // given
        PgAttemptHistoryInfo info = PgAttemptHistoryInfo.builder()
                .orderId(ORDER_ID)
                .found(true)
                .finalStatus("QUARANTINED")
                .finalizedAt(Instant.parse("2026-07-27T09:00:20Z"))
                .reasonCode("RETRY_EXHAUSTED")
                .attempts(List.of())
                .build();
        given(pgAttemptHistoryPort.getAttemptHistory(ORDER_ID)).willReturn(info);

        // when
        PgAttemptHistoryLookupResult result = sut.getAttemptHistory(ORDER_ID);

        // then
        assertThat(result.isUnavailable()).isFalse();
        assertThat(result.getHistory()).isSameAs(info);
    }

    @Test
    @DisplayName("포트 조회가 도메인 예외를 던지면 조회 불가 결과를 반환한다")
    void getAttemptHistory_domainException_returnsUnavailable() {
        // given
        willThrow(new IllegalStateException("unexpected pg response"))
                .given(pgAttemptHistoryPort).getAttemptHistory(ORDER_ID);

        // when
        PgAttemptHistoryLookupResult result = sut.getAttemptHistory(ORDER_ID);

        // then
        assertThat(result.isUnavailable()).isTrue();
        assertThat(result.getHistory()).isNull();
    }

    @Test
    @DisplayName("포트 조회가 재시도 가능(타임아웃) 예외를 던져도 조회 불가 결과로 흡수된다")
    void getAttemptHistory_retryableException_returnsUnavailable() {
        // given
        willThrow(PgAttemptHistoryServiceRetryableException.of(PaymentErrorCode.PG_SERVICE_UNAVAILABLE))
                .given(pgAttemptHistoryPort).getAttemptHistory(ORDER_ID);

        // when
        PgAttemptHistoryLookupResult result = sut.getAttemptHistory(ORDER_ID);

        // then
        assertThat(result.isUnavailable()).isTrue();
        assertThat(result.getHistory()).isNull();
    }
}
