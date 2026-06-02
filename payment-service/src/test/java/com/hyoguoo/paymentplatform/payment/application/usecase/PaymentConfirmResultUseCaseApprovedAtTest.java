package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T14 — PaymentConfirmResultUseCase.parseApprovedAt offset 정규화 검증 (AC9).
 *
 * <p>D8 — OffsetDateTime.parse().toInstant() 정규화로 오프셋 보존.
 * KST(+09:00) 입력이 UTC 절대시점으로 정확히 변환되어 9시간 오차가 없음을 단정한다.
 *
 * <p>AC9 — parseApprovedAt KST offset → UTC Instant 변환 정확도 검증:
 * .toLocalDateTime() 경로 사용 시 9시간 오차 발생 → .toInstant() 정규화로 해소.
 *
 * <p>minor — parseApprovedAt 을 package-private 으로 노출해 production 메서드를 직접 단정한다.
 */
@DisplayName("PaymentConfirmResultUseCase parseApprovedAt offset 정규화 테스트 (AC9)")
class PaymentConfirmResultUseCaseApprovedAtTest {

    @Test
    @DisplayName("AC9 — KST +09:00 입력은 production parseApprovedAt 경유 시 UTC Z 기준 절대시점으로 변환 (9시간 오차 없음)")
    void parseApprovedAt_kstOffset_shouldBeUTCInstant() {
        // given — KST 오전 9시 = UTC 자정
        String kstApprovedAt = "2026-01-01T09:00:00+09:00";

        // when — production parseApprovedAt 직접 호출 (package-private)
        Instant result = PaymentConfirmResultUseCase.parseApprovedAt(kstApprovedAt);

        // then — UTC 2026-01-01T00:00:00Z 와 동치 (9시간 오차 부재)
        Instant expectedUtc = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(result)
                .as("KST +09:00 오전 9시 = UTC 자정: parseApprovedAt 경유 시 9시간 오차 없음")
                .isEqualTo(expectedUtc);

        // 회귀 가드: production 이 .toLocalDateTime() 을 경유했다면 9시간 오차가 발생해 이 단정이 실패한다.
        assertThat(result)
                .as("9시간 오차 부재: result 는 2026-01-01T09:00:00Z 가 아닌 2026-01-01T00:00:00Z 여야 함")
                .isNotEqualTo(Instant.parse("2026-01-01T09:00:00Z"));
    }

    @Test
    @DisplayName("AC9 — UTC +00:00 입력은 production parseApprovedAt 경유 시 동일 절대시점 변환 (UTC 입력 무오차)")
    void parseApprovedAt_utcOffset_shouldBeIdentical() {
        // given — UTC 오프셋 직접 입력
        String utcApprovedAt = "2026-01-01T00:00:00+00:00";

        // when — production parseApprovedAt 직접 호출
        Instant result = PaymentConfirmResultUseCase.parseApprovedAt(utcApprovedAt);

        // then — 입력 시각 그대로 UTC 절대시점
        assertThat(result).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("AC9 — null 입력은 production parseApprovedAt 경유 시 IllegalArgumentException 발생")
    void parseApprovedAt_null_shouldThrow() {
        // when/then — production parseApprovedAt 직접 호출 시 null 체크 예외
        assertThatThrownBy(() -> PaymentConfirmResultUseCase.parseApprovedAt(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    @DisplayName("AC9 정적 검증 — .toLocalDateTime() 경로가 KST 입력에서 9시간 오차를 생성함을 명시")
    void parseApprovedAt_toLocalDateTimeRegressionGuard() {
        // production parseApprovedAt 이 .toInstant() 를 사용하므로 올바른 결과가 나온다.
        // 이 테스트는 .toLocalDateTime() 경로의 오차 크기를 역으로 증명한다.
        String kstApprovedAt = "2026-01-01T09:00:00+09:00";

        Instant correctInstant = PaymentConfirmResultUseCase.parseApprovedAt(kstApprovedAt);

        // .toLocalDateTime() 경로 — 오프셋 무시 → 09:00 을 UTC로 오해
        // (실제 구현에서 이 경로를 사용하면 정산 앵커에 9시간 오차 발생)
        Instant incorrectInstant = OffsetDateTime.parse(kstApprovedAt)
                .toLocalDateTime()
                .toInstant(java.time.ZoneOffset.UTC);

        assertThat(correctInstant)
                .as("parseApprovedAt 결과는 2026-01-01T00:00:00Z")
                .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(incorrectInstant)
                .as("toLocalDateTime() 경로는 09:00:00Z (9시간 오차)")
                .isEqualTo(Instant.parse("2026-01-01T09:00:00Z"));

        assertThat(correctInstant)
                .as("parseApprovedAt 와 toLocalDateTime() 경로는 KST 입력에서 9시간 차이")
                .isNotEqualTo(incorrectInstant);
    }
}
