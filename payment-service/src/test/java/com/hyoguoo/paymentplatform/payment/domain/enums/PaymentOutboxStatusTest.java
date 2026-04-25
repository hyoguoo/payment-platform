package com.hyoguoo.paymentplatform.payment.domain.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("K2-F10: PaymentOutboxStatus SSOT 메서드 테스트")
class PaymentOutboxStatusTest {

    @ParameterizedTest
    @CsvSource({
            "PENDING,  false",
            "IN_FLIGHT, false",
            "DONE,     true",
            "FAILED,   true",
    })
    @DisplayName("K2-F10: isTerminal() — DONE/FAILED=true, 나머지=false")
    void isTerminal(PaymentOutboxStatus status, boolean expected) {
        assertThat(status.isTerminal()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING,  true",
            "IN_FLIGHT, false",
            "DONE,     false",
            "FAILED,   false",
    })
    @DisplayName("K2-F10: isClaimable() — PENDING=true, 나머지=false")
    void isClaimable(PaymentOutboxStatus status, boolean expected) {
        assertThat(status.isClaimable()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING,  false",
            "IN_FLIGHT, true",
            "DONE,     false",
            "FAILED,   false",
    })
    @DisplayName("K2-F10: isInFlight() — IN_FLIGHT=true, 나머지=false")
    void isInFlight(PaymentOutboxStatus status, boolean expected) {
        assertThat(status.isInFlight()).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(PaymentOutboxStatus.class)
    @DisplayName("K2-F10: isTerminal/isClaimable/isInFlight — 각 상태별 4가지 값 일관성 (정확히 1개 그룹에 속함)")
    void eachStatus_belongsToExactlyOneGroup(PaymentOutboxStatus status) {
        boolean terminal = status.isTerminal();
        boolean claimable = status.isClaimable();
        boolean inFlight = status.isInFlight();

        // PENDING: claimable만 true
        // IN_FLIGHT: inFlight만 true
        // DONE/FAILED: terminal만 true
        long trueCount = (terminal ? 1 : 0) + (claimable ? 1 : 0) + (inFlight ? 1 : 0);
        assertThat(trueCount)
                .as("status=%s 는 정확히 1개 그룹에만 속해야 한다", status)
                .isEqualTo(1);
    }
}
