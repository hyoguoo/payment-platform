package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.fake;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.infrastructure.aspect.TossApiMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FakePgGatewayStrategy 계약 검증.
 *
 * <p>happy-path 반환 + getStatusByOrderId 미지원 계약에 더해, 데모 부하 관측용
 * 합성 벤더 latency 메트릭 기록과 fail-rate 주입(예외 throw) 동작을 검증한다.
 */
class FakePgGatewayStrategyTest {

    private static final PgConfirmRequest REQUEST = new PgConfirmRequest(
            "order-1", "fake-key-1234", BigDecimal.valueOf(1000), PgVendorType.TOSS);

    private FakePgGatewayStrategy strategy(MeterRegistry registry, double failRate) {
        // latency 0/0 → 테스트에서 sleep 없음.
        return new FakePgGatewayStrategy(Clock.systemUTC(), new TossApiMetrics(registry), failRate, 0, 0);
    }

    @Test
    void getStatusByOrderId_shouldThrowUnsupported() {
        assertThatThrownBy(() -> strategy(new SimpleMeterRegistry(), 0.0).getStatusByOrderId("some-order-id"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void confirm_failRateZero_shouldReturnSuccessAndRecordSuccessMetric() {
        MeterRegistry registry = new SimpleMeterRegistry();

        PgConfirmResult result = strategy(registry, 0.0).confirm(REQUEST);

        assertThat(result.isSuccess()).isTrue();
        assertThat(registry.get("toss.api.call.total").tag("status", "success").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void confirm_failRateAlways_shouldThrowNonRetryableAndRecordFailureMetric() {
        MeterRegistry registry = new SimpleMeterRegistry();
        FakePgGatewayStrategy strategy = strategy(registry, 1.0);

        assertThatThrownBy(() -> strategy.confirm(REQUEST))
                .isInstanceOf(PgGatewayNonRetryableException.class);
        assertThat(registry.get("toss.api.call.total").tag("status", "failure").counter().count())
                .isEqualTo(1.0);
    }
}
