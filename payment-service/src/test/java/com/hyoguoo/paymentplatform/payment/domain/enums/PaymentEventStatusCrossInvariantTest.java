package com.hyoguoo.paymentplatform.payment.domain.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("두 가드 교차 불변식 — 종결·QUARANTINED·EXPIRED 에서 canApplyConfirmResult / canCompensateStock 둘 다 false 동조")
class PaymentEventStatusCrossInvariantTest {

    @DisplayName("종결 및 격리 상태 (DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED) 에서 두 가드 모두 false")
    @ParameterizedTest
    @EnumSource(value = PaymentEventStatus.class, names = {"DONE", "FAILED", "CANCELED", "PARTIAL_CANCELED", "EXPIRED", "QUARANTINED"})
    void bothGuards_종결및격리상태_둘다false(PaymentEventStatus status) {
        assertThat(status.canApplyConfirmResult()).isFalse();
        assertThat(status.canCompensateStock()).isFalse();
        // 관계 불변식 명시 단언 — 한쪽만 true로 드리프트하면 RED
        assertThat(status.canApplyConfirmResult())
                .as("두 가드 QUARANTINED/EXPIRED 답 동조 불변식 — 한쪽 드리프트 시 D7 침묵 DLQ 재현")
                .isEqualTo(status.canCompensateStock());
    }

    @DisplayName("QUARANTINED — canApplyConfirmResult 와 canCompensateStock 동조 명시 단언")
    @Test
    void quarantined_canApply와canCompensate_동조_명시단언() {
        assertThat(PaymentEventStatus.QUARANTINED.canApplyConfirmResult()).isFalse();
        assertThat(PaymentEventStatus.QUARANTINED.canCompensateStock()).isFalse();
        assertThat(PaymentEventStatus.QUARANTINED.canApplyConfirmResult())
                .isEqualTo(PaymentEventStatus.QUARANTINED.canCompensateStock());
    }

    @DisplayName("EXPIRED — canApplyConfirmResult 와 canCompensateStock 동조 명시 단언")
    @Test
    void expired_canApply와canCompensate_동조_명시단언() {
        assertThat(PaymentEventStatus.EXPIRED.canApplyConfirmResult()).isFalse();
        assertThat(PaymentEventStatus.EXPIRED.canCompensateStock()).isFalse();
        assertThat(PaymentEventStatus.EXPIRED.canApplyConfirmResult())
                .isEqualTo(PaymentEventStatus.EXPIRED.canCompensateStock());
    }
}
