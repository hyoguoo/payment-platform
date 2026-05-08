package com.hyoguoo.paymentplatform.pg.application.port.out;

import com.hyoguoo.paymentplatform.pg.domain.PgInbox;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgInboxStatus;
import java.util.List;
import java.util.Optional;

/**
 * pg-service outbound 포트 — business inbox 저장소 계약.
 * order_id UNIQUE 보장 + 5상태 전이.
 *
 * <p>PCS-3: listener 경로 PENDING INSERT + 워커 TX_A + 보정 경로 직진 전이 + 좀비 조회 메서드 추가.
 */
public interface PgInboxRepository {

    Optional<PgInbox> findByOrderId(String orderId);

    PgInbox save(PgInbox inbox);

    /**
     * listener 경로 — PENDING row INSERT.
     * orderId UNIQUE 충돌 시 IGNORE 하고 기존 row 의 id 를 반환한다 (멱등 보장).
     *
     * @param orderId     orderId (UNIQUE)
     * @param amount      원화 최소 단위 정수
     * @param eventUuid   PG 콜백 이벤트 UUID (중복 방어용)
     * @param vendorType  벤더 타입 문자열 (e.g., "TOSS_PAYMENTS")
     * @param paymentKey  벤더 결제 키
     * @return 삽입 또는 기존 row 의 id
     */
    Long insertPending(String orderId, long amount, String eventUuid,
                       String vendorType, String paymentKey);

    /**
     * 워커 TX_A — PENDING → IN_PROGRESS SKIP LOCKED.
     * {@code SELECT FOR UPDATE SKIP LOCKED WHERE id=? AND status=PENDING} 후 상태 갱신.
     * 다른 워커가 이미 선점한 경우(SKIP LOCKED) false 를 반환한다.
     *
     * @param inboxId pg_inbox PK
     * @return true — 전이 성공(이 워커가 PG 호출 책임), false — 선점 실패
     */
    boolean transitPendingToInProgress(Long inboxId);

    /**
     * 보정 경로 — PENDING 우회, inbox 신설 + 바로 IN_PROGRESS.
     * {@link com.hyoguoo.paymentplatform.pg.application.service.DuplicateApprovalHandler}
     * 의 {@code handleDbAbsentAmountMatch} 에서 사용된다.
     *
     * @param orderId orderId (UNIQUE)
     * @param amount  원화 최소 단위 정수
     * @return 신설된 IN_PROGRESS inbox id
     */
    Long transitDirectToInProgress(String orderId, long amount);

    /**
     * 보정 경로 — PENDING + IN_PROGRESS 우회, inbox 신설 + 직접 terminal 전이.
     * {@code handleDbAbsentAmountMismatch} / {@code handleVendorIndeterminate} 에서 사용된다.
     *
     * @param orderId            orderId (UNIQUE)
     * @param amount             원화 최소 단위 정수
     * @param terminalStatus     목표 terminal 상태 (APPROVED / FAILED / QUARANTINED)
     * @param storedStatusResult 저장할 상태 결과 JSON
     * @param reasonCode         실패/격리 사유 코드 (terminal 이 APPROVED 이면 null 허용)
     * @return 신설된 terminal inbox id
     */
    Long transitDirectToTerminal(String orderId, long amount, PgInboxStatus terminalStatus,
                                 String storedStatusResult, String reasonCode);

    /**
     * 좀비 폴링 — PENDING 상태이며 임계 시간을 초과한 row id 목록 반환.
     * {@code WHERE status=PENDING AND received_at < :before} 조건으로 조회한다.
     *
     * @param batchSize   최대 반환 건수
     * @param thresholdMs PENDING 상태로 머문 최대 허용 시간 (밀리초). 이 값 이상 경과한 row 를 반환.
     * @return 좀비 PENDING inbox id 목록
     */
    List<Long> findPendingZombieIds(int batchSize, long thresholdMs);

    /**
     * 좀비 폴링 — IN_PROGRESS 상태이며 임계 시간을 초과한 row id 목록 반환.
     * {@code WHERE status=IN_PROGRESS AND updated_at < :before} 조건으로 조회한다.
     *
     * @param batchSize   최대 반환 건수
     * @param thresholdMs IN_PROGRESS 상태로 머문 최대 허용 시간 (밀리초). 이 값 이상 경과한 row 를 반환.
     * @return 좀비 IN_PROGRESS inbox id 목록
     */
    List<Long> findInProgressZombieIds(int batchSize, long thresholdMs);

    /**
     * NONE → IN_PROGRESS compare-and-set 원자 전이.
     * 최초 소비 스레드만 true 를 얻고 PG 호출로 진입한다 (orderId 레벨 단일 점유).
     * 이미 NONE 이 아닌(=다른 스레드가 선점한) 경우 false 를 반환한다.
     *
     * <p>실제 구현체(JPA): SELECT FOR UPDATE 또는 INSERT ON DUPLICATE KEY UPDATE.
     * Fake 구현체: ConcurrentHashMap.putIfAbsent + computeIfPresent 기반 원자 전이.
     *
     * @param orderId orderId (UNIQUE)
     * @param amount  원화 최소 단위 정수
     * @return true — 전이 성공(이 스레드가 PG 호출 책임), false — 전이 실패(다른 스레드 선점)
     * @deprecated PCS-9 에서 호출처 갱신 후 삭제 예정. listener 경로는 {@link #insertPending} 으로,
     *             워커 경로는 {@link #transitPendingToInProgress} 로 교체한다.
     */
    @Deprecated(forRemoval = true)
    boolean transitNoneToInProgress(String orderId, long amount);

    /**
     * IN_PROGRESS → APPROVED 전이. storedStatusResult 저장.
     * 벤더 호출 성공 후 terminal 상태로 전이한다.
     *
     * @param orderId           orderId (UNIQUE)
     * @param storedStatusResult 저장할 상태 결과 JSON
     */
    void transitToApproved(String orderId, String storedStatusResult);

    /**
     * IN_PROGRESS → FAILED 전이. storedStatusResult + reasonCode 저장.
     * 벤더 호출 확정 실패 후 terminal 상태로 전이한다.
     *
     * @param orderId           orderId (UNIQUE)
     * @param storedStatusResult 저장할 상태 결과 JSON
     * @param reasonCode        실패 사유 코드
     */
    void transitToFailed(String orderId, String storedStatusResult, String reasonCode);

    /**
     * non-terminal → QUARANTINED compare-and-set 전이.
     * DLQ consumer 가 inbox 를 격리 상태로 전이할 때 사용한다.
     * 이미 terminal(APPROVED/FAILED/QUARANTINED)이면 false 를 반환 (중복 DLQ 진입 흡수).
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
     * DLQ consumer 의 중복 진입을 막기 위해 SELECT FOR UPDATE 의미론을 사용한다.
     * Fake 구현체에서는 일반 findByOrderId 와 동등하게 동작한다.
     *
     * @param orderId orderId (UNIQUE)
     * @return inbox Optional
     */
    Optional<PgInbox> findByOrderIdForUpdate(String orderId);
}
