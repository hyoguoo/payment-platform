package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PaymentEventRepository {

    Optional<PaymentEvent> findById(Long id);

    Optional<PaymentEvent> findByOrderId(String orderId);

    PaymentEvent saveOrUpdate(PaymentEvent paymentEvent);

    List<PaymentEvent> findReadyPaymentsOlderThan(LocalDateTime before);

    Map<PaymentEventStatus, Long> countByStatus();

    long countByStatusAndExecutedAtBefore(PaymentEventStatus status, LocalDateTime before);

    long countByRetryCountGreaterThanEqual(int retryCount);

    /**
     * IN_PROGRESS 상태이며 executedAt이 before 이전인 레코드 목록 반환.
     * Reconciler가 timeout된 IN_FLIGHT 레코드를 READY로 복원할 때 사용.
     *
     * @param before 기준 시각 (이 시각 이전에 실행된 레코드)
     * @return timeout된 IN_PROGRESS 이벤트 목록
     */
    List<PaymentEvent> findInProgressOlderThan(LocalDateTime before);

    /**
     * 지정 상태의 모든 결제 이벤트 목록 반환.
     * Reconciler 재고 대조 및 QUARANTINED 스캔에 사용.
     *
     * @param status 조회할 상태
     * @return 해당 상태의 이벤트 목록
     */
    List<PaymentEvent> findAllByStatus(PaymentEventStatus status);
}
