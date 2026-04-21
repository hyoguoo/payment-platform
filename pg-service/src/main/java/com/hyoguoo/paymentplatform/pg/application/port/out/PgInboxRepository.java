package com.hyoguoo.paymentplatform.pg.application.port.out;

import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import java.util.Optional;

/**
 * pg-service outbound 포트 — business inbox 저장소 계약.
 * ADR-21: order_id UNIQUE 보장, 5상태 전이.
 * 구현체(JPA 어댑터)는 T2a-04에서 추가.
 */
public interface PgInboxRepository {

    Optional<PgInbox> findByOrderId(String orderId);

    PgInbox save(PgInbox inbox);

    /**
     * NONE → IN_PROGRESS compare-and-set 원자 전이.
     * ADR-04(2단 멱등성): 최초 소비 스레드만 true를 얻고 PG 호출로 진입한다.
     * 이미 NONE이 아닌(즉, 이미 선점된) 경우 false를 반환한다.
     *
     * <p>실제 구현체(JPA): SELECT FOR UPDATE 또는 INSERT ON DUPLICATE KEY UPDATE.
     * Fake 구현체: ConcurrentHashMap.putIfAbsent + computeIfPresent 기반 원자 전이.
     *
     * @param orderId orderId (UNIQUE)
     * @param amount  원화 최소 단위 정수
     * @return true — 전이 성공(이 스레드가 PG 호출 책임), false — 전이 실패(다른 스레드 선점)
     */
    boolean transitNoneToInProgress(String orderId, long amount);

    /**
     * IN_PROGRESS → APPROVED 전이. storedStatusResult 저장.
     * ADR-21: 벤더 호출 성공 후 terminal 상태로 전이.
     *
     * @param orderId           orderId (UNIQUE)
     * @param storedStatusResult 저장할 상태 결과 JSON
     */
    void transitToApproved(String orderId, String storedStatusResult);

    /**
     * IN_PROGRESS → FAILED 전이. storedStatusResult + reasonCode 저장.
     * ADR-21: 벤더 호출 확정 실패 후 terminal 상태로 전이.
     *
     * @param orderId           orderId (UNIQUE)
     * @param storedStatusResult 저장할 상태 결과 JSON
     * @param reasonCode        실패 사유 코드
     */
    void transitToFailed(String orderId, String storedStatusResult, String reasonCode);

    /**
     * non-terminal → QUARANTINED compare-and-set 전이.
     * ADR-30(T2b-02): DLQ consumer가 inbox를 격리 상태로 전이한다.
     * 이미 terminal(APPROVED/FAILED/QUARANTINED)이면 false를 반환 (불변식 6c 중복 DLQ 흡수).
     *
     * <p>실제 구현체(JPA): SELECT FOR UPDATE + 상태 검사 + UPDATE.
     * Fake 구현체: compute 기반 원자 전이.
     *
     * @param orderId    orderId (UNIQUE)
     * @param reasonCode 격리 사유 코드 (e.g., "RETRY_EXHAUSTED")
     * @return true — 전이 성공, false — 이미 terminal(no-op)
     */
    boolean transitToQuarantined(String orderId, String reasonCode);

    /**
     * FOR UPDATE 잠금을 포함한 inbox 조회.
     * ADR-30(T2b-02): DLQ consumer의 중복 진입 방어를 위해 SELECT FOR UPDATE 의미론 적용.
     * Fake 구현체에서는 일반 findByOrderId와 동등하게 동작한다.
     *
     * @param orderId orderId (UNIQUE)
     * @return inbox Optional
     */
    Optional<PgInbox> findByOrderIdForUpdate(String orderId);
}
