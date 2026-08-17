package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.List;
import java.util.Optional;

/**
 * 재고 캐시 outbound port — atomic decrement / rollback.
 * 운영 구현체는 Redis Lua atomic 어댑터({@code StockCacheRedisAdapter}).
 */
public interface StockCachePort {

    /**
     * 결제 단위 N개 상품 atomic 선차감 (dedup token SETNX).
     * 운영 구현체는 {@code stock_decrement_atomic.lua} 를 단일 Lua 호출로 실행한다.
     *
     * @param orderId        결제 주문 ID (dedup token key 에 사용)
     * @param paymentOrders  차감 대상 상품 목록 ({@link PaymentOrder#getProductId()} / {@link PaymentOrder#getQuantity()})
     * @return {@link StockDecrementAtomicResult#OK} 정상 차감,
     *         {@link StockDecrementAtomicResult#ALREADY_DONE} 동일 orderId 재진입,
     *         {@link StockDecrementAtomicResult#INSUFFICIENT} 재고 부족
     * @throws RuntimeException 인프라 장애 시 전파
     */
    StockDecrementAtomicResult decrementAtomic(String orderId, List<PaymentOrder> paymentOrders);

    /**
     * 결제 단위 N개 상품 atomic 보상 복원 (dedup token SETNX).
     * 운영 구현체는 {@code stock_compensation_atomic.lua} 를 단일 Lua 호출로 실행한다.
     *
     * @param orderId        결제 주문 ID (dedup token key 에 사용)
     * @param paymentOrders  복원 대상 상품 목록
     * @return {@link StockCompensationAtomicResult#OK} 정상 복원,
     *         {@link StockCompensationAtomicResult#ALREADY_DONE} 동일 orderId 재진입
     * @throws RuntimeException 인프라 장애 시 전파
     */
    StockCompensationAtomicResult compensateAtomic(String orderId, List<PaymentOrder> paymentOrders);

    /**
     * 격리(QUARANTINED) 복구 전용 조건부 보상 — {@code decrement:done} 토큰이 있을 때만 복원한다.
     * 운영 구현체는 {@code stock_compensation_if_decremented.lua} 를 단일 Lua 호출로 실행한다.
     *
     * <p>정상 흐름의 {@link #compensateAtomic} 과 달리, 선차감이 실제로 일어났는지(={@code decrement:done}
     * 존재) 먼저 확인한 뒤에만 복원한다 — 선차감이 없었던 주문을 잘못 복원해 유령 재고를 만드는 것을 방지한다.
     *
     * @param orderId        결제 주문 ID (dedup token key 에 사용)
     * @param paymentOrders  복원 대상 상품 목록
     * @return {@link StockRecoveryCompensationResult#OK} 정상 복원,
     *         {@link StockRecoveryCompensationResult#ALREADY_DONE} 동일 orderId 재진입,
     *         {@link StockRecoveryCompensationResult#NO_DECREMENT} 선차감 토큰 부재로 보상 미수행
     * @throws RuntimeException 인프라 장애 시 전파
     */
    StockRecoveryCompensationResult compensateIfDecremented(String orderId, List<PaymentOrder> paymentOrders);

    /**
     * 상품 하나에 대한 atomic 선차감.
     * 운영 구현체는 {@code stock_decrement_atomic.lua} 를 그 상품의 키에 대해 직접 실행한다.
     *
     * @param orderId      결제 주문 ID (dedup token key 에 사용)
     * @param paymentOrder 차감 대상 상품 ({@link PaymentOrder#getProductId()} / {@link PaymentOrder#getQuantity()})
     * @return {@link StockDecrementAtomicResult#OK} 정상 차감,
     *         {@link StockDecrementAtomicResult#ALREADY_DONE} 동일 주문·상품 조합 재진입,
     *         {@link StockDecrementAtomicResult#INSUFFICIENT} 재고 부족
     * @throws RuntimeException 인프라 장애 시 전파
     */
    StockDecrementAtomicResult decrementAtomic(String orderId, PaymentOrder paymentOrder);

    /**
     * 상품 하나에 대한 격리 복구 전용 조건부 보상 — {@code decrement:done} 토큰이 있을 때만 복원한다.
     * 운영 구현체는 {@code stock_compensation_if_decremented.lua} 를 그 상품의 키에 대해 직접 실행한다.
     *
     * @param orderId      결제 주문 ID (dedup token key 에 사용)
     * @param paymentOrder 복원 대상 상품
     * @return {@link StockRecoveryCompensationResult#OK} 정상 복원,
     *         {@link StockRecoveryCompensationResult#ALREADY_DONE} 동일 주문·상품 조합 재진입,
     *         {@link StockRecoveryCompensationResult#NO_DECREMENT} 선차감 토큰 부재로 보상 미수행
     * @throws RuntimeException 인프라 장애 시 전파
     */
    StockRecoveryCompensationResult compensateIfDecremented(String orderId, PaymentOrder paymentOrder);

    /**
     * 상품 하나에 대한 거절 전용 되돌리기 — 재고를 복원하면서 선차감 표시와 되돌리기 표시를 함께 지운다.
     * 운영 구현체는 {@code stock_reject_compensation.lua} 를 그 상품의 키에 대해 직접 실행한다.
     *
     * <p>두 표시를 함께 지워 이번 차감 사이클을 완전히 무효화한다 — 선차감 표시만 남으면 재시도가
     * 이미 처리됨으로 통과해 실제 차감 없이 승인되고, 되돌리기 표시만 남으면 재시도가 만든 새
     * 차감을 다음 사이클의 되돌리기가 못 돌린다. 이번 요청이 직접 차감에 성공한 상품에 한해서만
     * 호출해야 한다 — 흔적 유무만으로는 남의 차감을 되돌릴 수 있어 판정 기준이 되지 못한다.
     *
     * @param orderId      결제 주문 ID (표시 key 에 사용)
     * @param paymentOrder 되돌릴 대상 상품
     * @throws RuntimeException 인프라 장애 시 전파
     */
    void rejectCompensate(String orderId, PaymentOrder paymentOrder);

    /**
     * 확정 진입 시 상품 반복 전체를 감싸는 주문 단위 선점을 시도한다.
     *
     * <p>동시 중복 확정 요청이 하나로 수렴하게 하는 장치다. 명시적 해제({@link #releaseOrderLock})가
     * 주 해제 수단이고, 운영 구현체가 함께 거는 수명은 프로세스가 죽어 해제를 못한 경우를 위한
     * 회수용 backup 이다 — 수명만으로 풀리게 두면 처리가 그 시간을 넘길 때 두 번째 요청이
     * 재획득해 같은 주문의 상품 반복이 동시에 두 번 돌 수 있다.
     *
     * @param orderId 결제 주문 ID
     * @return 선점에 성공하면 해제 시 제시할 토큰, 이미 다른 요청이 선점 중이면 empty
     * @throws RuntimeException 인프라 장애 시 전파
     */
    Optional<String> acquireOrderLock(String orderId);

    /**
     * 주문 단위 선점을 명시적으로 해제한다.
     *
     * <p>{@link #acquireOrderLock} 이 돌려준 토큰과 일치할 때만 해제된다. 수명이 지나 다른
     * 요청이 재획득한 뒤에는 토큰이 달라 아무 일도 하지 않는다 — 남의 선점을 지우지 않는다.
     *
     * @param orderId   결제 주문 ID
     * @param lockToken {@link #acquireOrderLock} 이 돌려준 토큰
     * @throws RuntimeException 인프라 장애 시 전파
     */
    void releaseOrderLock(String orderId, String lockToken);

    /**
     * 재고 캐시를 product RDB(SoT) 기준 수량으로 덮어쓴다 — 운영 resync 전용.
     *
     * <p><b>주의</b>: 단순 SET 이라 in-flight 선차감(payment 가 confirm 중 차감한 미확정분)을 덮어쓴다.
     * 진행 중 결제가 있는 시점에 호출하면 over-sell 가능 — 운영자는 트래픽이 조용한 시점에,
     * 발산이 확인된 특정 productId 에 한해 호출해야 한다. 평상시 차감/복원은
     * {@link #decrementAtomic}/{@link #compensateAtomic} 가 담당하며 이 메서드를 거치지 않는다.
     *
     * @param productId 재고 키 {@code stock:{productId}}
     * @param quantity  product RDB 의 현재 재고 수량
     */
    void set(Long productId, int quantity);
}
