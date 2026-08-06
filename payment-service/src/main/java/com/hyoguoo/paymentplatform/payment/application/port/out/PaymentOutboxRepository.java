package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentOutboxRepository {

    PaymentOutbox save(PaymentOutbox paymentOutbox);

    /**
     * 주문 단위 발행 행 생성 전용 — 이미 있으면 조용히 넘어가는 삽입 + 확인 조회로 판정한다.
     *
     * <p>반영 행이 있으면 {@link PaymentOutboxCreationResult#CREATED}, 반영 행이 없고 확인 조회로
     * 기존 행이 잡히면 {@link PaymentOutboxCreationResult#ALREADY_EXISTS}(동시 재진입),
     * 확인 조회에도 행이 없으면 {@link PaymentOutboxCreationResult#SAVE_FAILED}(진짜 저장 실패)를 반환한다.
     *
     * <p>확인 조회는 블로킹 쓰기 잠금 읽기로 수행한다. 호출 트랜잭션은 이 삽입에 앞서 이미 다른
     * 읽기로 스냅샷을 잡은 상태이므로, 잠금 없는 조회로는 방금 자신을 막은 행을 보지 못해
     * 중복을 저장 실패로 오분류한다.
     *
     * @param orderId 주문 ID (UNIQUE)
     * @return 생성 결과 세 갈래
     */
    PaymentOutboxCreationResult createPendingIfAbsent(String orderId);

    Optional<PaymentOutbox> findByOrderId(String orderId);

    List<PaymentOutbox> findPendingBatch(int limit);

    List<PaymentOutbox> findTimedOutInFlight(Instant before);

    boolean claimToInFlight(String orderId, Instant inFlightAt);

    /**
     * 대기 상태 발행 재시도 간격을 조건부로 반영한다 — {@link #claimToInFlight}와 같은 형태의
     * 조건부 갱신이다.
     *
     * <p>발행 실패로 트랜잭션이 롤백되면 행은 대기 상태로 돌아가지만, 그 사이 다른 워커가 이미
     * 선점했거나(진행 중으로 전이) 같은 갱신을 먼저 마쳤을 수 있다. 읽은 시점의 상태·횟수를
     * 모두 조건으로 걸어, 둘 중 하나라도 어긋나면 반영 없이 0건으로 끝난다 — 상태만 조건으로
     * 걸면 다른 워커가 이미 같은 갱신을 마친 경우(횟수만 달라진 경우)를 놓친다.
     *
     * @param orderId            주문 ID
     * @param expectedRetryCount 읽은 시점의 재시도 횟수 (조건)
     * @param nextRetryCount     반영할 재시도 횟수
     * @param nextRetryAt        반영할 다음 시도 시각
     * @return 반영 행이 있으면 true
     */
    boolean recordRetryDelay(String orderId, int expectedRetryCount, int nextRetryCount, Instant nextRetryAt);

    // ── 관측 지표 집계 (Prometheus gauge) ───────────────────────────────────────

    /**
     * PENDING 상태 row 수를 반환한다.
     */
    long countPending();

    /**
     * PENDING 이면서 nextRetryAt &gt; now 인 row 수를 반환한다 (미래 예약 재시도).
     */
    long countFuturePending(Instant now);

    /**
     * PENDING row 중 가장 오래된 createdAt을 반환한다.
     * PENDING row가 없으면 Optional.empty().
     */
    Optional<Instant> findOldestPendingCreatedAt();
}
