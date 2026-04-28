package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.fake;

import java.time.Clock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FakePgGatewayStrategy.getStatusByOrderId 계약 검증.
 *
 * <p>Fake 전략은 smoke happy-path 전용이며 getStatusByOrderId 는
 * DuplicateApprovalHandler / PgFinalConfirmationGate(복구 사이클) 경로에서만 호출된다.
 * smoke 프로파일에서는 해당 경로가 트리거되지 않아야 하므로,
 * 호출 자체가 {@link UnsupportedOperationException} 으로 명시적 계약 위반을 알려야 한다.
 */
class FakePgGatewayStrategyTest {

    @Test
    void getStatusByOrderId_shouldThrowUnsupported() {
        FakePgGatewayStrategy strategy = new FakePgGatewayStrategy(Clock.systemUTC());

        assertThatThrownBy(() -> strategy.getStatusByOrderId("some-order-id"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
