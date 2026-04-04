package com.hyoguoo.paymentplatform.payment.scheduler;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import com.hyoguoo.paymentplatform.core.channel.PaymentConfirmChannel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("OutboxImmediateWorker 테스트")
class OutboxImmediateWorkerTest {

    @Test
    @DisplayName("이벤트를 채널에 제출하면 OutboxProcessingService가 호출된다")
    void 이벤트를채널에제출하면_OutboxProcessingService가호출된다() {
        // given
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        PaymentConfirmChannel channel = new PaymentConfirmChannel(1, meterRegistry);
        OutboxProcessingService mockService = Mockito.mock(OutboxProcessingService.class);
        OutboxImmediateWorker worker = new OutboxImmediateWorker(channel, mockService);
        ReflectionTestUtils.setField(worker, "workerCount", 1);
        ReflectionTestUtils.setField(worker, "virtualThreads", true);

        // when
        worker.start();
        channel.offer("order-1");

        // then
        await().atMost(1, SECONDS).untilAsserted(
                () -> verify(mockService, atLeastOnce()).process("order-1")
        );

        worker.stop(() -> {});
    }

    @Test
    @DisplayName("stop 호출 후 Worker가 중단된다")
    void stop_호출후_Worker가중단된다() {
        // given
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        PaymentConfirmChannel channel = new PaymentConfirmChannel(10, meterRegistry);
        OutboxProcessingService mockService = Mockito.mock(OutboxProcessingService.class);
        OutboxImmediateWorker worker = new OutboxImmediateWorker(channel, mockService);
        ReflectionTestUtils.setField(worker, "workerCount", 2);
        ReflectionTestUtils.setField(worker, "virtualThreads", true);

        AtomicBoolean callbackInvoked = new AtomicBoolean(false);

        // when
        worker.start();
        worker.stop(() -> callbackInvoked.set(true));

        // then
        await().atMost(5, SECONDS).untilTrue(callbackInvoked);
    }

    @Test
    @DisplayName("process에서 RuntimeException이 발생해도 Worker 스레드가 종료되지 않는다")
    void process_RuntimeException_Worker스레드가_종료되지_않는다() {
        // given
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        PaymentConfirmChannel channel = new PaymentConfirmChannel(10, meterRegistry);
        OutboxProcessingService mockService = Mockito.mock(OutboxProcessingService.class);
        Mockito.doThrow(new RuntimeException("test error"))
                .doNothing()
                .when(mockService).process(Mockito.anyString());

        OutboxImmediateWorker worker = new OutboxImmediateWorker(channel, mockService);
        ReflectionTestUtils.setField(worker, "workerCount", 1);
        ReflectionTestUtils.setField(worker, "virtualThreads", true);

        // when
        worker.start();
        channel.offer("order-1"); // 예외 발생
        channel.offer("order-2"); // 예외 이후에도 처리돼야 함

        // then
        await().atMost(1, SECONDS).untilAsserted(
                () -> verify(mockService, atLeastOnce()).process("order-2")
        );

        worker.stop(() -> {});
    }
}
