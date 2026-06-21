package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.fake;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.event.DuplicateApprovalDetectedEvent;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayDuplicateHandledException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.infrastructure.aspect.TossApiMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * FakePgGatewayStrategy 계약 검증.
 *
 * <p>happy-path 반환 + 동일 paymentKey 재호출 시 vendor 멱등성(중복 승인) 시뮬레이션에 더해,
 * 데모 부하 관측용 합성 벤더 latency 메트릭 기록과 fail-rate 주입(예외 throw) 동작을 검증한다.
 */
class FakePgGatewayStrategyTest {

    private static final PgConfirmRequest REQUEST = new PgConfirmRequest(
            "order-1", "fake-key-1234", BigDecimal.valueOf(1000), PgVendorType.TOSS);

    private FakePgGatewayStrategy strategy(MeterRegistry registry, double failRate, ApplicationEventPublisher publisher) {
        // latency 0/0 → 테스트에서 sleep 없음.
        return new FakePgGatewayStrategy(Clock.systemUTC(), new TossApiMetrics(registry), publisher, failRate, 0, 0);
    }

    private FakePgGatewayStrategy strategy(MeterRegistry registry, double failRate) {
        return strategy(registry, failRate, mock(ApplicationEventPublisher.class));
    }

    @Test
    void confirm_첫호출_SUCCESS_반환() {
        MeterRegistry registry = new SimpleMeterRegistry();

        PgConfirmResult result = strategy(registry, 0.0).confirm(REQUEST);

        assertThat(result.isSuccess()).isTrue();
        assertThat(registry.get("toss.api.call.total").tag("status", "success").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void confirm_동일paymentKey_재호출_DuplicateHandledException_및_이벤트발행() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        FakePgGatewayStrategy strategy = strategy(new SimpleMeterRegistry(), 0.0, publisher);

        PgConfirmResult firstResult = strategy.confirm(REQUEST);
        assertThat(firstResult.isSuccess()).isTrue();

        assertThatThrownBy(() -> strategy.confirm(REQUEST))
                .isInstanceOf(PgGatewayDuplicateHandledException.class);

        ArgumentCaptor<DuplicateApprovalDetectedEvent> eventCaptor =
                ArgumentCaptor.forClass(DuplicateApprovalDetectedEvent.class);
        then(publisher).should().publishEvent(eventCaptor.capture());
        DuplicateApprovalDetectedEvent publishedEvent = eventCaptor.getValue();
        assertThat(publishedEvent.orderId()).isEqualTo(REQUEST.orderId());
        assertThat(publishedEvent.paymentKey()).isEqualTo(REQUEST.paymentKey());
        assertThat(publishedEvent.amount()).isEqualTo(REQUEST.amount());
        assertThat(publishedEvent.vendorType()).isEqualTo(REQUEST.vendorType());
    }

    @Test
    void getStatusByOrderId_처리된orderId_happy응답() {
        FakePgGatewayStrategy strategy = strategy(new SimpleMeterRegistry(), 0.0);
        strategy.confirm(REQUEST);

        PgStatusResult statusResult = strategy.getStatusByOrderId(REQUEST.orderId());

        assertThat(statusResult.orderId()).isEqualTo(REQUEST.orderId());
        assertThat(statusResult.paymentKey()).isEqualTo(REQUEST.paymentKey());
        assertThat(statusResult.status()).isEqualTo(PgPaymentStatus.DONE);
        // amount 는 최초 confirm 과 동일해야 한다 — DuplicateApprovalHandler.handleDbExists 가
        // amount 불일치 시 QUARANTINED(AMOUNT_MISMATCH) 로 분기하기 때문.
        assertThat(statusResult.amount()).isEqualTo(REQUEST.amount());
    }

    @Test
    void getStatusByOrderId_미처리orderId_예외() {
        FakePgGatewayStrategy strategy = strategy(new SimpleMeterRegistry(), 0.0);

        assertThatThrownBy(() -> strategy.getStatusByOrderId("never-confirmed-order-id"))
                .isInstanceOf(UnsupportedOperationException.class);
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
