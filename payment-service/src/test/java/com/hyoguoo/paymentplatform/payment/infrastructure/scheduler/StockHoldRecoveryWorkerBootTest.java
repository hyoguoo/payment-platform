package com.hyoguoo.paymentplatform.payment.infrastructure.scheduler;

import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;

import com.hyoguoo.paymentplatform.payment.application.port.out.StockHoldRecordRepository;
import com.hyoguoo.paymentplatform.payment.application.usecase.StockHoldRecoveryUseCase;
import com.hyoguoo.paymentplatform.payment.core.config.SchedulerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code scheduler.enabled=true} 로 {@link SchedulerConfig}(&#64;EnableScheduling)가 활성화된
 * 최소 컨텍스트에서 {@link StockHoldRecoveryWorker#recover()}가 실제로 주기 실행되는지 확인한다.
 *
 * <p>이 프로젝트는 스케줄러 활성화 플래그(예: {@code application-docker.yml}의 {@code scheduler.enabled})가
 * 빠져 worker 코드는 정상이어도 실제로는 한 번도 기동하지 않은 이력이 있다 — 클래스 존재나
 * {@code @Scheduled} 어노테이션 유무를 보는 대신, 실제 Spring 컨텍스트를 띄워 짧은 주기로 반복
 * 호출되는지를 직접 확인한다. DB/Kafka/Redis 없이 이 worker 와 그 직접 의존만 등록한 최소
 * 컨텍스트라 Testcontainers 가 불필요하다.
 */
@SpringBootTest(
        classes = {SchedulerConfig.class, StockHoldRecoveryWorker.class, StockHoldRecoveryWorkerBootTest.TestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "scheduler.enabled=true",
        "scheduler.stock-hold-recovery-worker.fixed-delay-ms=100",
        "scheduler.stock-hold-recovery-worker.batch-size=10"
})
@DisplayName("StockHoldRecoveryWorker 부팅 테스트 — 스케줄러 실제 기동 확인")
class StockHoldRecoveryWorkerBootTest {

    @Autowired
    private StockHoldRecoveryUseCase stockHoldRecoveryUseCase;

    @Test
    @DisplayName("scheduler.enabled=true 면 짧은 주기로 recover 가 반복 호출된다")
    void 스케줄러가_실제로_기동한다() {
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> then(stockHoldRecoveryUseCase).should(atLeastOnce()).recover(10));
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        StockHoldRecoveryUseCase stockHoldRecoveryUseCase() {
            return Mockito.mock(StockHoldRecoveryUseCase.class);
        }

        @Bean
        StockHoldRecordRepository stockHoldRecordRepository() {
            StockHoldRecordRepository mock = Mockito.mock(StockHoldRecordRepository.class);
            given(mock.countNoise()).willReturn(0L);
            return mock;
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
