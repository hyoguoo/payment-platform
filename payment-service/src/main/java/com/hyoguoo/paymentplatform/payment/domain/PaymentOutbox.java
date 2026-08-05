package com.hyoguoo.paymentplatform.payment.domain;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentStatusException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder(builderMethodName = "allArgsBuilder", buildMethodName = "allArgsBuild")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentOutbox {

    private Long id;
    private String orderId;
    private PaymentOutboxStatus status;
    private int retryCount;
    private Instant nextRetryAt;
    private Instant inFlightAt;
    private Instant createdAt;
    private Instant updatedAt;

    public static PaymentOutbox createPending(String orderId) {
        return PaymentOutbox.allArgsBuilder()
                .orderId(orderId)
                .status(PaymentOutboxStatus.PENDING)
                .retryCount(0)
                .allArgsBuild();
    }

    public void toInFlight(Instant inFlightAt) {
        if (this.status != PaymentOutboxStatus.PENDING) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_IN_FLIGHT);
        }
        this.status = PaymentOutboxStatus.IN_FLIGHT;
        this.inFlightAt = inFlightAt;
    }

    public void toDone() {
        if (this.status != PaymentOutboxStatus.IN_FLIGHT) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_DONE);
        }
        this.status = PaymentOutboxStatus.DONE;
    }

    public void incrementRetryCount(RetryPolicy policy, Instant now) {
        // toInFlight/toDone 와 동일하게 IN_FLIGHT 상태에서만 허용.
        // DONE/FAILED outbox 에서 호출 시 silent 하게 PENDING 재활성화되던 버그 방어.
        if (this.status != PaymentOutboxStatus.IN_FLIGHT) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_RETRY);
        }
        this.retryCount++;
        this.status = PaymentOutboxStatus.PENDING;
        this.nextRetryAt = now.plus(policy.nextDelay(this.retryCount));
    }

    public void recordRetryDelay(RetryPolicy policy, Instant now) {
        // 발행 실패로 트랜잭션이 롤백되면 행은 이미 PENDING 이다 — incrementRetryCount 는
        // IN_FLIGHT 전용이라 이 자리에 쓸 수 없어 상태 전이 없는 별도 메서드로 나눴다.
        // 이 가드는 동시 선점을 막지 못한다: 읽은 시점에 PENDING 이었어도 저장 전에 다른
        // 워커가 선점할 수 있다. 실제 방어는 상태·횟수를 함께 거는 조건부 갱신이 한다.
        if (this.status != PaymentOutboxStatus.PENDING) {
            throw PaymentStatusException.of(PaymentErrorCode.INVALID_STATUS_TO_RECORD_RETRY_DELAY);
        }
        this.retryCount++;
        this.nextRetryAt = now.plus(policy.nextDelay(this.retryCount));
    }
}
