package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PaymentEventRepository {

    Optional<PaymentEvent> findById(Long id);

    Optional<PaymentEvent> findByOrderId(String orderId);

    PaymentEvent saveOrUpdate(PaymentEvent paymentEvent);

    List<PaymentEvent> findReadyPaymentsOlderThan(Instant before);

    Map<PaymentEventStatus, Long> countByStatus();

    long countByStatusAndExecutedAtBefore(PaymentEventStatus status, Instant before);

    /**
     * IN_PROGRESS 상태이며 executedAt이 before 이전인 레코드 목록 반환.
     * Reconciler가 timeout된 IN_FLIGHT 레코드를 READY로 복원할 때 사용.
     *
     * @param before 기준 시각 (이 시각 이전에 실행된 레코드)
     * @return timeout된 IN_PROGRESS 이벤트 목록
     */
    List<PaymentEvent> findInProgressOlderThan(Instant before);

    /**
     * 지정 상태의 모든 결제 이벤트 목록 반환.
     * Reconciler 재고 대조 및 QUARANTINED 스캔에 사용.
     *
     * @param status 조회할 상태
     * @return 해당 상태의 이벤트 목록
     */
    List<PaymentEvent> findAllByStatus(PaymentEventStatus status);

    /**
     * 격리(QUARANTINED) 결제 이벤트를 조건부(CAS)로 FAILED 종결한다.
     *
     * <p>DB 레벨 {@code WHERE status = 'QUARANTINED'} 게이트로 동시 복구 호출 race 를 차단하며,
     * 게이트 통과(1건 반영)일 때만 같은 트랜잭션에서 자식 {@code payment_order} 행도 FAIL 로 동조시킨다.
     * {@link #saveOrUpdate(PaymentEvent)} 는 event·order 를 별도 두 단계로 save 하므로(cascade 아님),
     * event 만 갱신하고 order 를 방치하면 상태 불일치가 영구 잔존한다 — 이 메서드는 그 갭을 막는다.
     *
     * @param paymentEventId     대상 결제 이벤트 id
     * @param reason             안전 종결 사유 (payment_event.status_reason)
     * @param lastStatusChangedAt 상태 변경 시각
     * @return true = 조건부 갱신 성공(1건 반영, 자식 order 도 FAIL 반영됨),
     *         false = 충돌(대상이 이미 QUARANTINED 가 아님, 0건, event·order 모두 불변)
     */
    boolean resolveQuarantineToFailed(Long paymentEventId, String reason, Instant lastStatusChangedAt);
}
