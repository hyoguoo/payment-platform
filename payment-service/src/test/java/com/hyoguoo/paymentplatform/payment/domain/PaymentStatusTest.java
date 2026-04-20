package com.hyoguoo.paymentplatform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayStatusUnmappedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaymentStatusTest {

    @Test
    @DisplayName("알 수 없는 PG 상태값이 들어오면 PaymentGatewayStatusUnmappedException을 던진다")
    void of_UnknownValue_ThrowsPaymentGatewayStatusUnmappedException() {
        assertThatThrownBy(() -> PaymentStatus.of("SOME_UNKNOWN_VALUE"))
                .isInstanceOf(PaymentGatewayStatusUnmappedException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "DONE,DONE",
            "CANCELED,CANCELED",
            "ABORTED,ABORTED",
            "EXPIRED,EXPIRED",
            "PARTIAL_CANCELED,PARTIAL_CANCELED",
            "IN_PROGRESS,IN_PROGRESS",
            "WAITING_FOR_DEPOSIT,WAITING_FOR_DEPOSIT"
    })
    @DisplayName("알려진 PG 상태값은 올바른 PaymentStatus로 매핑된다")
    void of_KnownValues_ReturnsCorrectStatus(String rawValue, PaymentStatus expected) {
        PaymentStatus result = PaymentStatus.of(rawValue);

        assertThat(result).isEqualTo(expected);
    }
}
