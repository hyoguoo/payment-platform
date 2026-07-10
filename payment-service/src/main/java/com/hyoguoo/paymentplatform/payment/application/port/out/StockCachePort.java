package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.List;

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
