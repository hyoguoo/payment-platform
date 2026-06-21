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
}
