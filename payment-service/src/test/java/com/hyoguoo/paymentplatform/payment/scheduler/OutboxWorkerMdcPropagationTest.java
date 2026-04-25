package com.hyoguoo.paymentplatform.payment.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hyoguoo.paymentplatform.core.config.Slf4jMdcThreadLocalAccessor;
import com.hyoguoo.paymentplatform.payment.application.service.OutboxRelayService;
import com.hyoguoo.paymentplatform.payment.application.port.out.MessagePublisherPort;
import io.micrometer.context.ContextRegistry;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxRepository;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;

/**
 * T-E1 RED — OutboxWorker.processParallel VT MDC 전파 확인.
 *
 * <p>MDC에 traceId=X 설정 후 processParallel 경로에서
 * relay 람다 내부에서 동일 값이 읽혀야 한다.
 * ContextExecutorService.wrap 적용 전에는 VT 경계에서 MDC가 비어 FAIL 한다.
 */
@DisplayName("OutboxWorker — processParallel MDC 전파 (T-E1 RED)")
class OutboxWorkerMdcPropagationTest {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String TRACE_ID_VALUE = "parallel-trace-xyz789";
    private static final String ORDER_ID_1 = "order-mdc-1";
    private static final String ORDER_ID_2 = "order-mdc-2";

    private PaymentOutboxUseCase mockPaymentOutboxUseCase;
    private CapturingOutboxRelayService capturingRelayService;
    private OutboxWorker outboxWorker;

    @BeforeEach
    void setUp() {
        // T-E1: 단위 테스트 환경에서는 Spring context 가 없으므로 MDC accessor 를 수동 등록
        ContextRegistry.getInstance().registerThreadLocalAccessor(new Slf4jMdcThreadLocalAccessor());
        mockPaymentOutboxUseCase = Mockito.mock(PaymentOutboxUseCase.class);
        capturingRelayService = new CapturingOutboxRelayService(2);
        // K6: 생성자 파라미터 @Value 이전 — ReflectionTestUtils 제거
        outboxWorker = new OutboxWorker(mockPaymentOutboxUseCase, capturingRelayService, 10, true, 5);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("processParallel — 호출 스레드 MDC traceId 가 VT 내부에서 승계된다")
    void processParallel_propagatesMdcToVirtualThreads() throws InterruptedException {
        // given: 호출 스레드에 traceId 설정 + pending 2건
        MDC.put(TRACE_ID_KEY, TRACE_ID_VALUE);
        List<PaymentOutbox> pending = List.of(
                createPendingOutbox(ORDER_ID_1),
                createPendingOutbox(ORDER_ID_2)
        );
        given(mockPaymentOutboxUseCase.findPendingBatch(10)).willReturn(pending);
        // recoverTimedOutInFlightRecords 는 void 반환 — mock 기본 동작(no-op) 사용

        // when
        outboxWorker.process();

        // then: 2개 relay 람다가 완료될 때까지 대기
        boolean allDone = capturingRelayService.latch.await(3, TimeUnit.SECONDS);

        assertThat(allDone).as("3초 이내에 VT 내부 MDC 캡처가 모두 완료되어야 한다").isTrue();
        assertThat(capturingRelayService.capturedTraceIds)
                .as("모든 VT 에서 호출 스레드의 traceId 가 승계되어야 한다")
                .hasSize(2)
                .containsOnly(TRACE_ID_VALUE);
    }

    private PaymentOutbox createPendingOutbox(String orderId) {
        return PaymentOutbox.allArgsBuilder()
                .id(1L)
                .orderId(orderId)
                .status(PaymentOutboxStatus.PENDING)
                .retryCount(0)
                .allArgsBuild();
    }

    /**
     * relay() 진입 시점의 MDC 값을 캡처하는 테스트 전용 relay service.
     * ContextExecutorService.wrap 적용 전에는 MDC 가 null 이므로 테스트가 FAIL 한다.
     */
    static class CapturingOutboxRelayService extends OutboxRelayService {

        final List<String> capturedTraceIds = new CopyOnWriteArrayList<>();
        final CountDownLatch latch;

        CapturingOutboxRelayService(int expectedCount) {
            super(
                    Mockito.mock(PaymentOutboxRepository.class),
                    Mockito.mock(MessagePublisherPort.class),
                    Mockito.mock(PaymentLoadUseCase.class),
                    Mockito.mock(LocalDateTimeProvider.class)
            );
            this.latch = new CountDownLatch(expectedCount);
        }

        @Override
        public void relay(String orderId) {
            capturedTraceIds.add(MDC.get(TRACE_ID_KEY));
            latch.countDown();
        }
    }
}
