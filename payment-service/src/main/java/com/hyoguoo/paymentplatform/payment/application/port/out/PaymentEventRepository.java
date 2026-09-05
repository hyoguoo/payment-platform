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

    /**
     * 주문 번호로 결제 이벤트를 잠금 읽기(PESSIMISTIC_WRITE)로 조회한다.
     *
     * <p>확정 결과 소비 경로({@code PaymentConfirmResultUseCase#handle})가 종결 상태 가드 판정 전에
     * 사용한다. 판정을 이 잠금 아래에서 하면 리컨실러의 조건부 전이(CAS)와 어느 순서로 겹쳐도 결과가
     * 맞다 — 컨슈머가 먼저 잠그면 리컨실러의 CAS 가 그 사이 0건으로 끝나고, 리컨실러가 먼저 잠갔다
     * 커밋하면 컨슈머가 바뀐 상태(예: 격리)를 보고 가드에서 물러난다. 잠금은 조회한 그 주문 한 행에만
     * 걸리며, {@link com.hyoguoo.paymentplatform.payment.application.port.out.PaymentOutboxRepository}
     * 의 잠금 읽기 확인 조회와 같은 형태다.
     *
     * @param orderId 주문 ID
     * @return 잠근 상태의 결제 이벤트, 없으면 empty
     */
    Optional<PaymentEvent> findByOrderIdForUpdate(String orderId);

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

    /**
     * IN_PROGRESS 결제 이벤트를 조건부(CAS)로 AWAITING_RESULT 로 옮긴다.
     *
     * <p>DB 레벨 {@code WHERE status = 'IN_PROGRESS'} 게이트로, 그 사이 확정된 건을 덮어쓰지 않는다.
     * {@code payment_event} 테이블만 갱신하며 {@code payment_order} 는 어떤 경우에도 건드리지 않는다 —
     * 이 전이는 도메인상 주문 상태를 바꾸지 않는다.
     *
     * @param paymentEventId      대상 결제 이벤트 id
     * @param lastStatusChangedAt 상태 변경 시각 (2차 임계 조회의 앵커)
     * @return true = 조건부 갱신 성공(1건 반영), false = 충돌(대상이 이미 IN_PROGRESS 가 아님, 0건, 불변)
     */
    boolean resolveInProgressToAwaitingResult(Long paymentEventId, Instant lastStatusChangedAt);

    /**
     * AWAITING_RESULT 결제 이벤트를 조건부(CAS)로 QUARANTINED 로 옮긴다.
     *
     * <p>DB 레벨 {@code WHERE status = 'AWAITING_RESULT'} 게이트로, 그 사이 확정된 건을 덮어쓰지 않는다.
     * {@code payment_event} 테이블만 갱신하며 {@code payment_order} 는 어떤 경우에도 건드리지 않는다 —
     * 이 전이는 도메인상 주문 상태를 바꾸지 않는다. 잘못 반영되면 되돌릴 경로가 없으므로 호출부가
     * 이 반환값을 반드시 확인해야 한다.
     *
     * @param paymentEventId      대상 결제 이벤트 id
     * @param reason              격리 사유 (payment_event.status_reason)
     * @param lastStatusChangedAt 상태 변경 시각
     * @return true = 조건부 갱신 성공(1건 반영), false = 충돌(대상이 이미 AWAITING_RESULT 가 아님, 0건, 불변)
     */
    boolean resolveAwaitingResultToQuarantine(Long paymentEventId, String reason, Instant lastStatusChangedAt);

    /**
     * AWAITING_RESULT 상태이며 lastStatusChangedAt 이 before 이전인 레코드 목록 반환.
     * Reconciler 2차 스캔이 결과 대기에 머문 시간을 판정할 때 사용한다.
     *
     * <p>앵커는 {@code executedAt} 이 아니라 {@code lastStatusChangedAt} 이다. {@code executedAt} 은
     * 확정 진입 시각으로 최초 한 번만 세팅되고 이후 갱신되지 않아, 되돌리기로 방금 결과 대기에 들어온
     * 건까지 오래 머문 것으로 오판하게 된다.
     *
     * @param before 기준 시각 (이 시각 이전에 상태가 변경된 레코드)
     * @return 2차 임계를 초과한 AWAITING_RESULT 이벤트 목록
     */
    List<PaymentEvent> findAwaitingResultOlderThan(Instant before);
}
