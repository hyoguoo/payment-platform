package com.hyoguoo.paymentplatform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentProcessStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PaymentProcess 도메인 테스트")
class PaymentProcessTest {

    @Nested
    @DisplayName("작업 생성 테스트")
    class CreateTest {

        @Test
        @DisplayName("주문 ID로 재고 작업을 생성하면 PROCESSING 상태로 시작된다")
        void createPaymentProcess() {
            // given
            String orderId = "test-order-123";

            // when
            PaymentProcess inventoryJob = PaymentProcess.createProcessing(orderId);

            // then
            assertThat(inventoryJob.getOrderId()).isEqualTo(orderId);
            assertThat(inventoryJob.getStatus()).isEqualTo(PaymentProcessStatus.PROCESSING);
        }
    }

    @Nested
    @DisplayName("작업 완료 처리 테스트")
    class CompleteTest {

        @Test
        @DisplayName("PROCESSING 상태의 작업을 완료 처리할 수 있다")
        void completeFromProcessing() {
            // given
            PaymentProcess inventoryJob = PaymentProcess.createProcessing("order-123");
            LocalDateTime completedAt = LocalDateTime.now();

            // when
            inventoryJob.complete(completedAt);

            // then
            assertThat(inventoryJob.getStatus()).isEqualTo(PaymentProcessStatus.COMPLETED);
            assertThat(inventoryJob.getCompletedAt()).isEqualTo(completedAt);
        }

        @Test
        @DisplayName("COMPLETED 상태의 작업은 다시 완료 처리할 수 없다 (멱등성)")
        void completeFromCompleted() {
            // given
            PaymentProcess inventoryJob = PaymentProcess.createProcessing("order-123");
            inventoryJob.complete(LocalDateTime.now());

            // when
            LocalDateTime newCompletedAt = LocalDateTime.now().plusMinutes(1);
            inventoryJob.complete(newCompletedAt);

            // then - 상태는 그대로 유지되고 예외가 발생하지 않음
            assertThat(inventoryJob.getStatus()).isEqualTo(PaymentProcessStatus.COMPLETED);
        }

        @Test
        @DisplayName("FAILED 상태의 작업은 완료 처리할 수 없다")
        void completeFromFailed() {
            // given
            PaymentProcess inventoryJob = PaymentProcess.createProcessing("order-123");
            inventoryJob.fail(LocalDateTime.now(), "payment failed");

            // when & then
            assertThatThrownBy(() -> inventoryJob.complete(LocalDateTime.now()))
                    .isInstanceOf(PaymentStatusException.class);
        }
    }

    @Nested
    @DisplayName("작업 실패 처리 테스트")
    class FailTest {

        @Test
        @DisplayName("PROCESSING 상태의 작업을 실패 처리할 수 있다")
        void failFromProcessing() {
            // given
            PaymentProcess inventoryJob = PaymentProcess.createProcessing("order-123");
            LocalDateTime failedAt = LocalDateTime.now();
            String reason = "payment timeout";

            // when
            inventoryJob.fail(failedAt, reason);

            // then
            assertThat(inventoryJob.getStatus()).isEqualTo(PaymentProcessStatus.FAILED);
            assertThat(inventoryJob.getFailedAt()).isEqualTo(failedAt);
            assertThat(inventoryJob.getFailureReason()).isEqualTo(reason);
        }

        @Test
        @DisplayName("FAILED 상태의 작업은 다시 실패 처리할 수 없다 (멱등성)")
        void failFromFailed() {
            // given
            PaymentProcess inventoryJob = PaymentProcess.createProcessing("order-123");
            inventoryJob.fail(LocalDateTime.now(), "first failure");

            // when
            LocalDateTime newFailedAt = LocalDateTime.now().plusMinutes(1);
            inventoryJob.fail(newFailedAt, "second failure");

            // then - 상태는 그대로 유지되고 예외가 발생하지 않음
            assertThat(inventoryJob.getStatus()).isEqualTo(PaymentProcessStatus.FAILED);
            assertThat(inventoryJob.getFailureReason()).isEqualTo("first failure");
        }

        @Test
        @DisplayName("COMPLETED 상태의 작업은 실패 처리할 수 없다")
        void failFromCompleted() {
            // given
            PaymentProcess inventoryJob = PaymentProcess.createProcessing("order-123");
            inventoryJob.complete(LocalDateTime.now());

            // when & then
            assertThatThrownBy(() -> inventoryJob.fail(LocalDateTime.now(), "reason"))
                    .isInstanceOf(PaymentStatusException.class);
        }
    }

    @Nested
    @DisplayName("멱등성 검증 테스트")
    class IdempotencyTest {

        @Test
        @DisplayName("완료된 작업을 여러 번 완료 처리해도 안전하다")
        void multipleCompleteCallsShouldBeSafe() {
            // given
            PaymentProcess inventoryJob = PaymentProcess.createProcessing("order-123");
            LocalDateTime firstCompletedAt = LocalDateTime.now();

            // when
            inventoryJob.complete(firstCompletedAt);
            inventoryJob.complete(LocalDateTime.now().plusSeconds(1));
            inventoryJob.complete(LocalDateTime.now().plusSeconds(2));

            // then
            assertThat(inventoryJob.getStatus()).isEqualTo(PaymentProcessStatus.COMPLETED);
            assertThat(inventoryJob.getCompletedAt()).isEqualTo(firstCompletedAt);
        }

        @Test
        @DisplayName("실패한 작업을 여러 번 실패 처리해도 안전하다")
        void multipleFailCallsShouldBeSafe() {
            // given
            PaymentProcess inventoryJob = PaymentProcess.createProcessing("order-123");
            LocalDateTime firstFailedAt = LocalDateTime.now();
            String firstReason = "first failure";

            // when
            inventoryJob.fail(firstFailedAt, firstReason);
            inventoryJob.fail(LocalDateTime.now().plusSeconds(1), "second failure");
            inventoryJob.fail(LocalDateTime.now().plusSeconds(2), "third failure");

            // then
            assertThat(inventoryJob.getStatus()).isEqualTo(PaymentProcessStatus.FAILED);
            assertThat(inventoryJob.getFailedAt()).isEqualTo(firstFailedAt);
            assertThat(inventoryJob.getFailureReason()).isEqualTo(firstReason);
        }
    }
}
