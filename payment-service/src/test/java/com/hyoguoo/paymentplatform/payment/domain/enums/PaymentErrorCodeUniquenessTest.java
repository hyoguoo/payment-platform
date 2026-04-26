package com.hyoguoo.paymentplatform.payment.domain.enums;

import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PaymentErrorCode 코드 유일성 검증.
 * 동일 error code(예: E03002)가 2개 이상 enum 상수에 할당되지 않았음을 컴파일 타임이 아닌
 * 런타임 자동 검증으로 회귀를 차단한다.
 */
@DisplayName("PaymentErrorCode 코드 유일성 검증")
class PaymentErrorCodeUniquenessTest {

    @Test
    @DisplayName("모든 PaymentErrorCode enum 상수의 code 값은 중복이 없어야 한다")
    void allPaymentErrorCodes_ShouldHaveUniqueCodes() {
        PaymentErrorCode[] values = PaymentErrorCode.values();
        Set<String> uniqueCodes = Arrays.stream(values)
                .map(PaymentErrorCode::getCode)
                .collect(Collectors.toSet());

        assertThat(uniqueCodes)
                .as("PaymentErrorCode에 중복 code가 존재합니다. 각 enum 상수는 고유한 code를 가져야 합니다.")
                .hasSize(values.length);
    }
}
