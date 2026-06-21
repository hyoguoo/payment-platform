package com.hyoguoo.paymentplatform.payment.domain.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("PaymentEventStatus 분리 판별 메서드 — canApplyConfirmResult")
class PaymentEventStatusSplitMethodTest {

    // --- canApplyConfirmResult ---

    @DisplayName("canApplyConfirmResult — 진입 가능 상태 (READY / IN_PROGRESS) 는 true")
    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"READY", "IN_PROGRESS"})
    void canApplyConfirmResult_진입가능상태_trueを返す(PaymentEventStatus status) {
        assertThat(status.canApplyConfirmResult()).isTrue();
    }

    @DisplayName("canApplyConfirmResult — 진입 불가 상태 (DONE / FAILED / CANCELED / PARTIAL_CANCELED / EXPIRED / QUARANTINED) 는 false")
    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"DONE", "FAILED", "CANCELED", "PARTIAL_CANCELED", "EXPIRED", "QUARANTINED"})
    void canApplyConfirmResult_진입불가상태_false返す(PaymentEventStatus status) {
        assertThat(status.canApplyConfirmResult()).isFalse();
    }
}
