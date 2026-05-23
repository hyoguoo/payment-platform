package com.hyoguoo.paymentplatform.payment.application.dto.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ConfirmStatus.from — 문자열 → enum 변환")
class ConfirmStatusTest {

    @ParameterizedTest
    @CsvSource({"APPROVED,APPROVED", "FAILED,FAILED", "QUARANTINED,QUARANTINED"})
    @DisplayName("알려진 상태 문자열은 대응 enum 으로 변환된다")
    void from_knownStatus(String raw, ConfirmStatus expected) {
        assertThat(ConfirmStatus.from(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("null 은 UNKNOWN 으로 흡수된다")
    void from_null() {
        assertThat(ConfirmStatus.from(null)).isEqualTo(ConfirmStatus.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "approved", "GARBAGE", "PENDING"})
    @DisplayName("알 수 없는 문자열은 UNKNOWN 으로 흡수된다")
    void from_unknown(String raw) {
        assertThat(ConfirmStatus.from(raw)).isEqualTo(ConfirmStatus.UNKNOWN);
    }
}
