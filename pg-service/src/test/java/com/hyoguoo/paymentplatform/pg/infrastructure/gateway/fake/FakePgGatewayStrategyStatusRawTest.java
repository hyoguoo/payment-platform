package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.fake;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.infrastructure.aspect.TossApiMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * FakePgGatewayStrategy 조회 결과 승인 시각 원문 보존 — 보관 중인 승인 결과의 원문을 그대로 넘긴다.
 */
@DisplayName("FakePgGatewayStrategy 조회 결과 승인 시각 원문 보존")
class FakePgGatewayStrategyStatusRawTest {

    private static final PgConfirmRequest REQUEST = new PgConfirmRequest(
            "order-status-raw", "fake-key-status-raw", BigDecimal.valueOf(1000), PgVendorType.TOSS);

    @Test
    @DisplayName("승인기록_원문이_승인응답과_동일하다")
    void getStatusByOrderId_승인기록_원문이_승인응답과_동일하다() {
        FakePgGatewayStrategy strategy = new FakePgGatewayStrategy(
                Clock.systemUTC(), new TossApiMetrics(new SimpleMeterRegistry()),
                mock(ApplicationEventPublisher.class), new MockEnvironment(), 0.0, 0, 0);

        PgConfirmResult confirmed = strategy.confirm(REQUEST);
        PgStatusResult statusResult = strategy.getStatusByOrderId(REQUEST.orderId());

        assertThat(statusResult.approvedAtRaw()).isEqualTo(confirmed.approvedAtRaw());
        assertThat(statusResult.approvedAtRaw()).isNotNull();
    }
}
