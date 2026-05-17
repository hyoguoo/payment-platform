package com.hyoguoo.paymentplatform.payment.domain.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("PaymentEventStatus EOS 진입 가드 — isCompensatableByFailureHandler() 회귀 테스트")
class PaymentEventStatusEosGuardTest {

    @DisplayName("isCompensatableByFailureHandler — 진입 가능 상태 (READY / IN_PROGRESS / RETRYING)")
    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"READY", "IN_PROGRESS", "RETRYING"})
    void shouldReturnTrueForProceedableStatuses(PaymentEventStatus status) {
        assertThat(status.isCompensatableByFailureHandler()).isTrue();
    }

    @DisplayName("isCompensatableByFailureHandler — 진입 불가 상태 (DONE / FAILED / CANCELED / PARTIAL_CANCELED / EXPIRED / QUARANTINED)")
    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"DONE", "FAILED", "CANCELED", "PARTIAL_CANCELED", "EXPIRED", "QUARANTINED"})
    void shouldReturnFalseForNonProceedableStatuses(PaymentEventStatus status) {
        assertThat(status.isCompensatableByFailureHandler()).isFalse();
    }

    @DisplayName("isCompensatableByFailureHandler — QUARANTINED 는 늦은 APPROVED 시 DLQ silent 분기 회피용 명시 가드 (DR-3)")
    @Test
    void shouldSkipQuarantinedExplicitly() {
        assertThat(PaymentEventStatus.QUARANTINED.isCompensatableByFailureHandler()).isFalse();
    }
}
