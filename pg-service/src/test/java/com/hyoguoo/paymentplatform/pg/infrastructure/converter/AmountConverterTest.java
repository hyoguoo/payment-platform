package com.hyoguoo.paymentplatform.pg.infrastructure.converter;

import com.hyoguoo.paymentplatform.pg.application.util.AmountConverter;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AmountConverter 단위 테스트.
 * discuss-domain-5 minor(BigDecimal → BIGINT 변환 규약) 검증.
 */
@DisplayName("AmountConverter — BigDecimal → long 변환 규약")
class AmountConverterTest {

    @Test
    @DisplayName("fromBigDecimalStrict — scale=0 양수 BigDecimal → long 정상 변환")
    void fromBigDecimalStrict_WhenValidAmount_ShouldReturnLong() {
        // when
        long result = AmountConverter.fromBigDecimalStrict(new BigDecimal("15000"));

        // then
        assertThat(result).isEqualTo(15000L);
    }

    @Test
    @DisplayName("fromBigDecimalStrict — null 입력 시 IllegalArgumentException")
    void fromBigDecimalStrict_WhenNull_ShouldThrowIllegalArgumentException() {
        assertThatThrownBy(() -> AmountConverter.fromBigDecimalStrict(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("fromBigDecimalStrict — scale>0 이지만 정수 값인 경우 long 정상 반환 (trailing zeros 허용)")
    void fromBigDecimalStrict_WhenScaleGtZeroButIntegral_ShouldReturnLong() {
        // Kafka JSON 역직렬화 시 1000.00 형태로 들어오는 케이스
        long result = AmountConverter.fromBigDecimalStrict(new BigDecimal("1000.00"));

        assertThat(result).isEqualTo(1000L);
    }

    @Test
    @DisplayName("fromBigDecimalStrict — 진짜 소수 입력 시 ArithmeticException (fractional part 거부)")
    void fromBigDecimalStrict_WhenScaleNotZero_ShouldThrowArithmeticException() {
        assertThatThrownBy(() -> AmountConverter.fromBigDecimalStrict(new BigDecimal("150.50")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("fromBigDecimalStrict — 음수 입력 시 거부 (amount must be non-negative)")
    void fromBigDecimalStrict_WhenNegative_ShouldThrow() {
        assertThatThrownBy(() -> AmountConverter.fromBigDecimalStrict(new BigDecimal("-1000")))
                .isInstanceOfAny(ArithmeticException.class, IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    @DisplayName("fromBigDecimalStrict — BigDecimal(\"0\") 입력 시 0L 반환")
    void fromBigDecimalStrict_WhenZero_ShouldReturnZero() {
        long result = AmountConverter.fromBigDecimalStrict(new BigDecimal("0"));

        assertThat(result).isEqualTo(0L);
    }
}
