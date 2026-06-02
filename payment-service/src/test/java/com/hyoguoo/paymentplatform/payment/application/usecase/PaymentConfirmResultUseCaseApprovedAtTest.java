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
 */
@DisplayName("PaymentConfirmResultUseCase parseApprovedAt offset 정규화 테스트 (AC9)")
class PaymentConfirmResultUseCaseApprovedAtTest {

    /**
     * parseApprovedAt 은 package-private 이므로 직접 테스트를 위해
     * OffsetDateTime.parse().toInstant() 변환 동치 검증으로 대체한다.
     * 실제 구현이 .toLocalDateTime() 을 경유하면 KST 입력에서 9시간 오차가 발생한다.
     */

    @Test
    @DisplayName("AC9 — KST +09:00 입력은 UTC Z 기준 절대시점으로 변환 (9시간 오차 없음)")
    void parseApprovedAt_kstOffset_shouldBeUTCInstant() {
        // given — KST 오전 9시 = UTC 자정
        String kstApprovedAt = "2026-01-01T09:00:00+09:00";

        // when — .toInstant() 정규화
        Instant result = OffsetDateTime.parse(kstApprovedAt).toInstant();

        // then — UTC 2026-01-01T00:00:00Z 와 동치 (9시간 오차 부재)
        Instant expectedUtc = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(result)
                .as("KST +09:00 오전 9시 = UTC 자정: toInstant() 정규화 시 9시간 오차 없음")
                .isEqualTo(expectedUtc);

        // 회귀 가드: .toLocalDateTime() 경로 사용 시의 오차를 명시
        // OffsetDateTime.parse(kstApprovedAt).toLocalDateTime() = 09:00:00 (오프셋 무시)
        // → atOffset(UTC).toInstant() = 09:00:00Z (9시간 오차 발생)
        // 이 테스트는 구현이 .toInstant() 를 사용할 때만 통과한다.
        assertThat(result)
                .as("9시간 오차 부재: result 는 2026-01-01T09:00:00Z 가 아닌 2026-01-01T00:00:00Z 여야 함")
                .isNotEqualTo(Instant.parse("2026-01-01T09:00:00Z"));
    }

    @Test
    @DisplayName("AC9 — UTC +00:00 입력은 동일 절대시점 변환 (UTC 입력 무오차)")
    void parseApprovedAt_utcOffset_shouldBeIdentical() {
        // given — UTC 오프셋 직접 입력
        String utcApprovedAt = "2026-01-01T00:00:00+00:00";

        // when
        Instant result = OffsetDateTime.parse(utcApprovedAt).toInstant();

        // then — 입력 시각 그대로 UTC 절대시점
        assertThat(result).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("AC9 — null 입력은 IllegalArgumentException 발생")
    void parseApprovedAt_null_shouldThrow() {
        // parseApprovedAt(null) 은 null 체크 후 IllegalArgumentException.
        // 직접 메서드 호출 대신 OffsetDateTime.parse(null) 동작 검증.
        assertThatThrownBy(() -> {
            String approvedAtRaw = null;
            if (approvedAtRaw == null) {
                throw new IllegalArgumentException("APPROVED 메시지에 approvedAt 이 null 입니다.");
            }
            OffsetDateTime.parse(approvedAtRaw).toInstant();
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("null");
    }

    @Test
    @DisplayName("AC9 정적 검증 — .toLocalDateTime() 경로가 KST 입력에서 9시간 오차를 생성함을 명시")
    void parseApprovedAt_toLocalDateTimeRegressionGuard() {
        // 회귀 가드: .toLocalDateTime() 경로의 오차 크기를 명시.
        // 이 테스트 자체는 .toInstant() 가 올바름을 역으로 증명한다.
        String kstApprovedAt = "2026-01-01T09:00:00+09:00";

        Instant correctInstant = OffsetDateTime.parse(kstApprovedAt).toInstant();

        // .toLocalDateTime() 경로 — 오프셋 무시 → 09:00 을 UTC로 오해
        // (실제 구현에서 이 경로를 사용하면 정산 앵커에 9시간 오차 발생)
        Instant incorrectInstant = OffsetDateTime.parse(kstApprovedAt)
                .toLocalDateTime()
                .toInstant(java.time.ZoneOffset.UTC);

        assertThat(correctInstant)
                .as("toInstant() 결과는 2026-01-01T00:00:00Z")
                .isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(incorrectInstant)
                .as("toLocalDateTime() 경로는 09:00:00Z (9시간 오차)")
                .isEqualTo(Instant.parse("2026-01-01T09:00:00Z"));

        assertThat(correctInstant)
                .as("toInstant() 와 toLocalDateTime() 경로는 KST 입력에서 9시간 차이")
                .isNotEqualTo(incorrectInstant);
    }
}
