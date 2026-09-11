package com.hyoguoo.paymentplatform.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentQuarantineMetrics;
import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentReconcilerBatchMetrics;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.mock.FakePaymentEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * PaymentReconciler 를 실제 {@link PaymentCommandUseCase}(AOP 프록시 없이 직접 조립) + 실제
 * {@link FakePaymentEventRepository} 로 조립해, 위임 메서드의 조건부 전이(CAS) 계약이 이 Fake 위에서
 * 실제로 성립하는지 확인한다. Mock 으로 대체한 {@link PaymentReconcilerTest} 는 위임 메서드 반환값을
 * 미리 정해두므로 Fake 내부의 조회·되쓰기 경로 버그를 잡지 못한다.
 *
 * <p>AOP(@Transactional, @PublishDomainEvent 등)는 Spring 컨테이너 없이 직접 new 로 조립한
 * 이 테스트에서는 적용되지 않는다 — 감사 이벤트 발행·트랜잭션 경계는 검증 대상이 아니고, 위임
 * 메서드의 반환값 계약(전이 성공 시 객체 / 충돌 시 null)만 단정한다.
 */
@DisplayName("PaymentReconciler + FakePaymentEventRepository 조립")
class PaymentReconcilerFakeRepositoryTest {

    private static final long TIMEOUT_SECONDS = 300;
    private static final long AWAITING_RESULT_TIMEOUT_SECONDS = 900;
    private static final Instant FIXED_INSTANT = Instant.parse("2026-04-27T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    private static final String ORDER_ID = "order-reconcile-assembled-001";

    @Test
    @DisplayName("실제 리컨실러와 실제 위임메서드 조립시 결과대기 전이가 성공한다")
    void 실제_리컨실러와_실제_위임메서드_조립시_결과대기_전이가_성공한다() {
        FakePaymentEventRepository fakeRepository = new FakePaymentEventRepository();
        PaymentEvent staleInProgress = PaymentEvent.allArgsBuilder()
                .id(1L)
                .buyerId(100L)
                .sellerId(200L)
                .orderName("테스트 상품")
                .orderId(ORDER_ID)
                .status(PaymentEventStatus.IN_PROGRESS)
                .executedAt(FIXED_INSTANT.minus(Duration.ofSeconds(TIMEOUT_SECONDS * 2)))
                .lastStatusChangedAt(FIXED_INSTANT.minus(Duration.ofSeconds(TIMEOUT_SECONDS * 2)))
                .paymentOrderList(List.of())
                .allArgsBuild();
        fakeRepository.save(staleInProgress);

        PaymentCommandUseCase paymentCommandUseCase = new PaymentCommandUseCase(
                fakeRepository, FIXED_CLOCK, new PaymentQuarantineMetrics(new SimpleMeterRegistry()));
        PaymentReconcilerBatchMetrics paymentReconcilerBatchMetrics =
                Mockito.mock(PaymentReconcilerBatchMetrics.class);
        PaymentReconciler reconciler = new PaymentReconciler(
                fakeRepository,
                paymentCommandUseCase,
                paymentReconcilerBatchMetrics,
                FIXED_CLOCK,
                TIMEOUT_SECONDS,
                AWAITING_RESULT_TIMEOUT_SECONDS
        );

        reconciler.scan();

        then(paymentReconcilerBatchMetrics).should(never()).recordRaceSkip(any());
        then(paymentReconcilerBatchMetrics).should(never()).recordFailure(any(), any());
        PaymentEvent persisted = fakeRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(PaymentEventStatus.AWAITING_RESULT);
    }
}
