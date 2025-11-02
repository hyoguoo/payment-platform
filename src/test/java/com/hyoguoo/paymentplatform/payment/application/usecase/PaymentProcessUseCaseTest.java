package com.hyoguoo.paymentplatform.payment.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentProcessRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentProcess;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentProcessStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("PaymentProcessUseCase 테스트")
@ExtendWith(MockitoExtension.class)
class PaymentProcessUseCaseTest {

    @InjectMocks
    private PaymentProcessUseCase inventoryJobUseCase;

    @Mock
    private PaymentProcessRepository inventoryJobRepository;

    @Mock
    private LocalDateTimeProvider localDateTimeProvider;

    @Nested
    @DisplayName("PROCESSING 상태 작업 생성 테스트")
    class CreateProcessingJobTest {

        @Test
        @DisplayName("주문 ID로 PROCESSING 상태의 재고 작업을 생성한다")
        void createProcessingJob() {
            // given
            String orderId = "order-123";
            PaymentProcess expectedJob = PaymentProcess.createProcessing(orderId);
            PaymentProcess savedJob = PaymentProcess.allArgsBuilder()
                    .id(1L)
                    .orderId(orderId)
                    .status(PaymentProcessStatus.PROCESSING)
                    .allArgsBuild();

            given(inventoryJobRepository.save(any(PaymentProcess.class)))
                    .willReturn(savedJob);

            // when
            PaymentProcess result = inventoryJobUseCase.createProcessingJob(orderId);

            // then
            assertThat(result.getOrderId()).isEqualTo(orderId);
            assertThat(result.getStatus()).isEqualTo(PaymentProcessStatus.PROCESSING);
            assertThat(result.getId()).isEqualTo(1L);

            then(inventoryJobRepository).should(times(1)).save(any(PaymentProcess.class));
        }
    }

    @Nested
    @DisplayName("작업 완료 처리 테스트")
    class CompleteJobTest {

        @Test
        @DisplayName("PROCESSING 상태의 작업을 COMPLETED로 전환한다")
        void completeProcessingJob() {
            // given
            String orderId = "order-123";
            LocalDateTime now = LocalDateTime.now();
            PaymentProcess processingJob = PaymentProcess.allArgsBuilder()
                    .id(1L)
                    .orderId(orderId)
                    .status(PaymentProcessStatus.PROCESSING)
                    .allArgsBuild();

            given(localDateTimeProvider.now()).willReturn(now);
            given(inventoryJobRepository.findByOrderId(orderId))
                    .willReturn(Optional.of(processingJob));
            given(inventoryJobRepository.save(any(PaymentProcess.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            PaymentProcess result = inventoryJobUseCase.completeJob(orderId);

            // then
            assertThat(result.getStatus()).isEqualTo(PaymentProcessStatus.COMPLETED);
            assertThat(result.getCompletedAt()).isEqualTo(now);

            then(inventoryJobRepository).should(times(1)).findByOrderId(orderId);
            then(inventoryJobRepository).should(times(1)).save(any(PaymentProcess.class));
        }

        @Test
        @DisplayName("존재하지 않는 주문 ID로 완료 처리 시 예외가 발생한다")
        void completeNonExistentJob() {
            // given
            String orderId = "non-existent-order";

            given(inventoryJobRepository.findByOrderId(orderId))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> inventoryJobUseCase.completeJob(orderId))
                    .isInstanceOf(PaymentFoundException.class);

            then(inventoryJobRepository).should(times(1)).findByOrderId(orderId);
            then(inventoryJobRepository).should(never()).save(any(PaymentProcess.class));
        }

        @Test
        @DisplayName("이미 COMPLETED 상태인 작업은 재실행하지 않는다 (멱등성)")
        void completeAlreadyCompletedJob() {
            // given
            String orderId = "order-123";
            LocalDateTime originalCompletedAt = LocalDateTime.now().minusMinutes(10);
            PaymentProcess completedJob = PaymentProcess.allArgsBuilder()
                    .id(1L)
                    .orderId(orderId)
                    .status(PaymentProcessStatus.COMPLETED)
                    .completedAt(originalCompletedAt)
                    .allArgsBuild();

            given(inventoryJobRepository.findByOrderId(orderId))
                    .willReturn(Optional.of(completedJob));
            given(inventoryJobRepository.save(any(PaymentProcess.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            PaymentProcess result = inventoryJobUseCase.completeJob(orderId);

            // then
            assertThat(result.getStatus()).isEqualTo(PaymentProcessStatus.COMPLETED);
            assertThat(result.getCompletedAt()).isEqualTo(originalCompletedAt); // 원래 시각 유지

            then(localDateTimeProvider).should(never()).now(); // 시간 조회 안 함
        }
    }

    @Nested
    @DisplayName("작업 실패 처리 테스트")
    class FailJobTest {

        @Test
        @DisplayName("PROCESSING 상태의 작업을 FAILED로 전환한다")
        void failProcessingJob() {
            // given
            String orderId = "order-123";
            String failureReason = "payment timeout";
            LocalDateTime now = LocalDateTime.now();
            PaymentProcess processingJob = PaymentProcess.allArgsBuilder()
                    .id(1L)
                    .orderId(orderId)
                    .status(PaymentProcessStatus.PROCESSING)
                    .allArgsBuild();

            given(localDateTimeProvider.now()).willReturn(now);
            given(inventoryJobRepository.findByOrderId(orderId))
                    .willReturn(Optional.of(processingJob));
            given(inventoryJobRepository.save(any(PaymentProcess.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            PaymentProcess result = inventoryJobUseCase.failJob(orderId, failureReason);

            // then
            assertThat(result.getStatus()).isEqualTo(PaymentProcessStatus.FAILED);
            assertThat(result.getFailedAt()).isEqualTo(now);
            assertThat(result.getFailureReason()).isEqualTo(failureReason);

            then(inventoryJobRepository).should(times(1)).findByOrderId(orderId);
            then(inventoryJobRepository).should(times(1)).save(any(PaymentProcess.class));
        }

        @Test
        @DisplayName("존재하지 않는 주문 ID로 실패 처리 시 예외가 발생한다")
        void failNonExistentJob() {
            // given
            String orderId = "non-existent-order";
            String failureReason = "test failure";

            given(inventoryJobRepository.findByOrderId(orderId))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> inventoryJobUseCase.failJob(orderId, failureReason))
                    .isInstanceOf(PaymentFoundException.class);

            then(inventoryJobRepository).should(times(1)).findByOrderId(orderId);
            then(inventoryJobRepository).should(never()).save(any(PaymentProcess.class));
        }

        @Test
        @DisplayName("이미 FAILED 상태인 작업은 재실행하지 않는다 (멱등성)")
        void failAlreadyFailedJob() {
            // given
            String orderId = "order-123";
            String originalReason = "original failure";
            String newReason = "new failure";
            LocalDateTime originalFailedAt = LocalDateTime.now().minusMinutes(10);
            PaymentProcess failedJob = PaymentProcess.allArgsBuilder()
                    .id(1L)
                    .orderId(orderId)
                    .status(PaymentProcessStatus.FAILED)
                    .failedAt(originalFailedAt)
                    .failureReason(originalReason)
                    .allArgsBuild();

            given(inventoryJobRepository.findByOrderId(orderId))
                    .willReturn(Optional.of(failedJob));
            given(inventoryJobRepository.save(any(PaymentProcess.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            PaymentProcess result = inventoryJobUseCase.failJob(orderId, newReason);

            // then
            assertThat(result.getStatus()).isEqualTo(PaymentProcessStatus.FAILED);
            assertThat(result.getFailedAt()).isEqualTo(originalFailedAt); // 원래 시각 유지
            assertThat(result.getFailureReason()).isEqualTo(originalReason); // 원래 사유 유지

            then(localDateTimeProvider).should(never()).now(); // 시간 조회 안 함
        }
    }

    @Nested
    @DisplayName("PROCESSING 상태 작업 조회 테스트")
    class FindAllProcessingJobsTest {

        @Test
        @DisplayName("PROCESSING 상태의 모든 작업을 조회한다")
        void findAllProcessingJobs() {
            // given
            PaymentProcess job1 = PaymentProcess.allArgsBuilder()
                    .id(1L)
                    .orderId("order-1")
                    .status(PaymentProcessStatus.PROCESSING)
                    .allArgsBuild();
            PaymentProcess job2 = PaymentProcess.allArgsBuilder()
                    .id(2L)
                    .orderId("order-2")
                    .status(PaymentProcessStatus.PROCESSING)
                    .allArgsBuild();

            given(inventoryJobRepository.findAllByStatus(PaymentProcessStatus.PROCESSING))
                    .willReturn(java.util.List.of(job1, job2));

            // when
            var result = inventoryJobUseCase.findAllProcessingJobs();

            // then
            assertThat(result).hasSize(2);
            assertThat(result).extracting(PaymentProcess::getStatus)
                    .containsOnly(PaymentProcessStatus.PROCESSING);

            then(inventoryJobRepository).should(times(1))
                    .findAllByStatus(PaymentProcessStatus.PROCESSING);
        }
    }
}
