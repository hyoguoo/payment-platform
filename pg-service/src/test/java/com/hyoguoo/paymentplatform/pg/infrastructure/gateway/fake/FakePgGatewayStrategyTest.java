package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.fake;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.event.DuplicateApprovalDetectedEvent;
import com.hyoguoo.paymentplatform.pg.domain.RetryPolicy;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayDuplicateHandledException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
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

    private PgConfirmRequest requestWithKey(String orderId, String paymentKey) {
        return new PgConfirmRequest(orderId, paymentKey, BigDecimal.valueOf(1000), PgVendorType.TOSS);
    }

    @Test
    void confirm_확정실패_접두어_NonRetryable_예외() {
        MeterRegistry registry = new SimpleMeterRegistry();
        FakePgGatewayStrategy strategy = strategy(registry, 0.0);
        PgConfirmRequest request = requestWithKey("order-drill-fail", "fake-fail-1234");

        assertThatThrownBy(() -> strategy.confirm(request))
                .isInstanceOf(PgGatewayNonRetryableException.class);
        assertThat(registry.get("toss.api.call.total").tag("status", "failure").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void confirm_재시도_접두어_매호출_Retryable_예외() {
        MeterRegistry registry = new SimpleMeterRegistry();
        FakePgGatewayStrategy strategy = strategy(registry, 0.0);
        PgConfirmRequest request = requestWithKey("order-drill-retry", "fake-retry-1234");

        assertThatThrownBy(() -> strategy.confirm(request))
                .isInstanceOf(PgGatewayRetryableException.class);
        assertThatThrownBy(() -> strategy.confirm(request))
                .isInstanceOf(PgGatewayRetryableException.class);
        assertThatThrownBy(() -> strategy.confirm(request))
                .isInstanceOf(PgGatewayRetryableException.class);
        assertThat(registry.get("toss.api.call.total").tag("status", "failure").counter().count())
                .isEqualTo(3.0);
    }

    @Test
    void confirm_자가회복_접두어_두번실패후_세번째_성공() {
        FakePgGatewayStrategy strategy = strategy(new SimpleMeterRegistry(), 0.0);
        PgConfirmRequest request = requestWithKey("order-drill-flaky", "fake-flaky-1234");

        assertThatThrownBy(() -> strategy.confirm(request))
                .isInstanceOf(PgGatewayRetryableException.class);
        assertThatThrownBy(() -> strategy.confirm(request))
                .isInstanceOf(PgGatewayRetryableException.class);

        PgConfirmResult result = strategy.confirm(request);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void confirm_자가회복_접두어_주문별_카운터_독립() {
        FakePgGatewayStrategy strategy = strategy(new SimpleMeterRegistry(), 0.0);
        PgConfirmRequest requestA = requestWithKey("order-drill-flaky-a", "fake-flaky-aaaa");
        PgConfirmRequest requestB = requestWithKey("order-drill-flaky-b", "fake-flaky-bbbb");

        // order-drill-flaky-a 를 회복 시점(3번째 호출 성공)까지 소진시킨다.
        assertThatThrownBy(() -> strategy.confirm(requestA))
                .isInstanceOf(PgGatewayRetryableException.class);
        assertThatThrownBy(() -> strategy.confirm(requestA))
                .isInstanceOf(PgGatewayRetryableException.class);
        assertThat(strategy.confirm(requestA).isSuccess()).isTrue();

        // order-drill-flaky-b 는 a 의 누적과 무관하게 첫 호출부터 다시 실패해야 한다 — 카운터가 주문별로 독립적이어야 함.
        assertThatThrownBy(() -> strategy.confirm(requestB))
                .isInstanceOf(PgGatewayRetryableException.class);
    }

    @Test
    void 자가회복_실패횟수가_재시도한도보다_작다() {
        FakePgGatewayStrategy strategy = strategy(new SimpleMeterRegistry(), 0.0);
        PgConfirmRequest request = requestWithKey("order-drill-flaky-bound", "fake-flaky-bound");

        // RetryPolicy.MAX_ATTEMPTS 회 안에서 자가 회복이 일어나는지 확인한다.
        // 자가 회복 실패 횟수가 MAX_ATTEMPTS 이상이 되면 회복 전에 시도가 소진되어
        // "자가 회복" 장면이 "격리" 장면으로 조용히 바뀐다.
        int successAttempt = findFlakySuccessAttempt(strategy, request);

        assertThat(successAttempt)
                .as("자가 회복은 RetryPolicy.MAX_ATTEMPTS(%d)에 도달하기 전에 성공해야 한다",
                        RetryPolicy.MAX_ATTEMPTS)
                .isPositive()
                .isLessThan(RetryPolicy.MAX_ATTEMPTS);
    }

    /**
     * RetryPolicy.MAX_ATTEMPTS 회까지 confirm 을 재시도하며, 자가 회복(성공)에 도달한 attempt 번호를 반환한다.
     * 한도 안에서 회복하지 못하면 -1.
     */
    private int findFlakySuccessAttempt(FakePgGatewayStrategy strategy, PgConfirmRequest request) {
        for (int attempt = 1; attempt <= RetryPolicy.MAX_ATTEMPTS; attempt++) {
            try {
                strategy.confirm(request);
                return attempt;
            } catch (PgGatewayRetryableException e) {
                // 계속 재시도 — 다음 attempt 로 진행
            }
        }
        return -1;
    }
}
