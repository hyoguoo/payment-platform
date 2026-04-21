package com.hyoguoo.paymentplatform.pg.infrastructure.converter;

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
    @DisplayName("fromBigDecimalStrict — scale>0 입력 시 ArithmeticException (scale must be 0)")
    void fromBigDecimalStrict_WhenScaleNotZero_ShouldThrowArithmeticException() {
        assertThatThrownBy(() -> AmountConverter.fromBigDecimalStrict(new BigDecimal("150.50")))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("scale must be 0");
    }

    @Test
    @DisplayName("fromBigDecimalStrict — 음수 입력 시 거부 (amount must be non-negative)")
    void fromBigDecimalStrict_WhenNegative_ShouldThrow() {
        assertThatThrownBy(() -> AmountConverter.fromBigDecimalStrict(new BigDecimal("-1000")))
                .isInstanceOfAny(ArithmeticException.class, IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
    }
}
