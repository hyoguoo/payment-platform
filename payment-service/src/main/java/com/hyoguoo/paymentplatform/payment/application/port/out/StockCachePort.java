package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.List;
import java.util.Optional;

/**
 * 재고 캐시 outbound port — atomic decrement / rollback / 조회 / 설정.
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
     * 재고 캐시 차감.
     *
     * @param productId 상품 ID
     * @param quantity  차감 수량
     * @return true: 차감 성공, false: 재고 부족으로 차감 거부
     */
    boolean decrement(Long productId, int quantity);

    /**
     * 재고 캐시 차감 롤백.
     *
     * @param productId 상품 ID
     * @param quantity  복원 수량
     */
    void rollback(Long productId, int quantity);

    /**
     * 재고 캐시 현재 수량 조회.
     *
     * @param productId 상품 ID
     * @return 현재 캐시 재고 수량 (key 없으면 0)
     */
    int current(Long productId);

    /**
     * 재고 캐시 현재 수량 조회 (key miss 감지용).
     * TTL 만료 또는 key 부재 시 Optional.empty() 반환.
     * 값이 0인 경우에도 Optional.of(0) 반환하여 miss와 구분.
     *
     * @param productId 상품 ID
     * @return 캐시에 key가 있으면 Optional.of(value), 없으면 Optional.empty()
     */
    Optional<Integer> findCurrent(Long productId);

    /**
     * 재고 캐시 증가 (confirmTx 실패 시 보상용).
     *
     * <p>executeConfirmTx 실패 후 decrementStock 에서 차감한 수량을 복원한다.
     * rollback 과 달리 보상 맥락이므로 별도 메서드로 분리.
     *
     * @param productId 상품 ID
     * @param quantity  복원 수량
     */
    void increment(Long productId, int quantity);

    /**
     * 재고 캐시 수량 설정 (warmup·대조 보정용).
     *
     * @param productId 상품 ID
     * @param quantity  설정 수량
     */
    void set(Long productId, int quantity);
}
