package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.nicepay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.nicepay.dto.NicepayPaymentApiResponse;
import com.hyoguoo.paymentplatform.pg.infrastructure.http.EncodeUtils;
import com.hyoguoo.paymentplatform.pg.infrastructure.http.HttpOperator;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * NicePay paidAt offset 정규화 회귀 방지.
 *
 * <p>NicePay 응답 paidAt 은 {@code yyyy-MM-dd'T'HH:mm:ss.SSSZ} 패턴 — offset 이 {@code +0900}
 * (RFC 822, 콜론 없음). 한편 {@code ConfirmedEventPayload.approvedAt} contract 는
 * ISO_OFFSET_DATE_TIME ({@code +09:00}, 콜론 필수) 표준이라, payment-service
 * {@code PaymentConfirmResultUseCase.parseApprovedAt} 의 {@link OffsetDateTime#parse(CharSequence)}
 * 가 RFC 822 형식을 거부한다.
 *
 * <p>회귀 시: NicePay 결제는 confirm 후 ConfirmedEvent 가 발행되지만, payment-service consumer 가
 * {@link java.time.format.DateTimeParseException} 으로 9 회 retry 모두 실패하고 결제가 FAILED 처리된다.
 * 따라서 NicePay 어댑터가 raw paidAt 을 OffsetDateTime 으로 한 번 파싱한 뒤 {@code .toString()} 으로
 * ISO_OFFSET_DATE_TIME 형식으로 정규화해 PgConfirmResult.approvedAtRaw 에 보존해야 한다.
 */
@DisplayName("NicepayPaymentGatewayStrategy paidAt 정규화")
class NicepayPaymentGatewayStrategyPaidAtNormalizationTest {

    private HttpOperator httpOperator;
    private NicepayPaymentGatewayStrategy strategy;

    @BeforeEach
    void setUp() {
        httpOperator = mock(HttpOperator.class);
        EncodeUtils encodeUtils = mock(EncodeUtils.class);
        when(encodeUtils.encodeBase64(anyString())).thenReturn("dummy-basic");

        strategy = new NicepayPaymentGatewayStrategy(
                httpOperator,
                encodeUtils,
                mock(ApplicationEventPublisher.class),
                new ObjectMapper());
        ReflectionTestUtils.setField(strategy, "clientKey", "S2_dummy");
        ReflectionTestUtils.setField(strategy, "secretKey", "secret-dummy");
        ReflectionTestUtils.setField(strategy, "nicepayApiUrl", "https://sandbox-api.nicepay.co.kr");
    }

    @ParameterizedTest(name = "NicePay paidAt={0} → approvedAtRaw={1}")
    @CsvSource({
            "2026-04-26T15:20:38.123+0900, 2026-04-26T15:20:38.123+09:00",
            "2026-04-26T15:20:38.000+0900, 2026-04-26T15:20:38+09:00",
            "2026-04-26T15:20:38.500+0530, 2026-04-26T15:20:38.500+05:30"
    })
    @DisplayName("RFC 822 offset(+0900) 을 ISO_OFFSET_DATE_TIME(+09:00) 으로 정규화한다")
    void RFC822_offset_을_ISO_OFFSET_DATE_TIME_으로_정규화한다(String rawPaidAt, String expected) {
        NicepayPaymentApiResponse response = new NicepayPaymentApiResponse(
                "0000", "정상", "tid-001", "order-001",
                BigDecimal.valueOf(1000), "paid", rawPaidAt);
        when(httpOperator.requestPost(
                anyString(), anyMap(), any(), eq(NicepayPaymentApiResponse.class)))
                .thenReturn(response);

        PgConfirmResult result = strategy.confirm(new PgConfirmRequest(
                "order-001", "tid-001", BigDecimal.valueOf(1000), PgVendorType.NICEPAY));

        assertThat(result.approvedAtRaw())
                .as("ConfirmedEventPayload.approvedAt contract 는 ISO_OFFSET_DATE_TIME 이다")
                .isEqualTo(expected);
        assertThatCode(() -> OffsetDateTime.parse(result.approvedAtRaw()))
                .as("payment-service OffsetDateTime.parse 가 거부하면 9 회 retry 후 FAILED 가 된다")
                .doesNotThrowAnyException();
    }
}
