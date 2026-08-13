package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.fake;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayConcurrentCallException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayDuplicateHandledException;
import com.hyoguoo.paymentplatform.pg.infrastructure.aspect.TossApiMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FakePgGatewayStrategy 의 겹친 호출(처리 중 거부) 재현 검증.
 *
 * <p>실 벤더는 승인 결과를 확정하기 전까지도 "처리 중"이라는 상태를 노출해, 그 구간에 겹쳐 들어온
 * 두 번째 호출을 겹침 거부(409)로 응답한다. 이를 재현하려면 응답이 지연되는 구간을 인위로 만들어야
 * 하므로, 벤더 latency 주입 설정으로 원 호출을 붙잡아두고 두 번째 호출을 스레드로 겹친다.
 */
class FakePgGatewayStrategyConcurrentCallTest {

    private static final PgConfirmRequest REQUEST = new PgConfirmRequest(
            "order-concurrent-1", "fake-key-concurrent", BigDecimal.valueOf(1000), PgVendorType.TOSS);

    private FakePgGatewayStrategy strategyWithLatency(long latencyMillis) {
        return new FakePgGatewayStrategy(
                Clock.systemUTC(), new TossApiMetrics(new SimpleMeterRegistry()),
                new MockEnvironment(), 0.0, latencyMillis, latencyMillis);
    }

    @Test
    void 원호출_응답대기중_두번째호출은_처리중거부() throws Exception {
        FakePgGatewayStrategy strategy = strategyWithLatency(300);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<PgConfirmResult> firstCall = executor.submit(() -> strategy.confirm(REQUEST));

            // 원 호출이 벤더 latency 로 응답을 늦추는 구간을 겨냥해 두 번째 호출을 겹친다.
            Thread.sleep(50);

            assertThatThrownBy(() -> strategy.confirm(REQUEST))
                    .isInstanceOf(PgGatewayConcurrentCallException.class);

            PgConfirmResult firstResult = firstCall.get(2, TimeUnit.SECONDS);
            assertThat(firstResult.isSuccess()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 원호출_완료후_두번째호출은_기존대로_이미처리됨() {
        FakePgGatewayStrategy strategy = strategyWithLatency(0);
        strategy.confirm(REQUEST);

        assertThatThrownBy(() -> strategy.confirm(REQUEST))
                .isInstanceOf(PgGatewayDuplicateHandledException.class);
    }
}
