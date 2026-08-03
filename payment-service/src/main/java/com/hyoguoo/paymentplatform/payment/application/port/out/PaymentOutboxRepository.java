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
