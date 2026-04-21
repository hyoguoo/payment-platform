package com.hyoguoo.paymentplatform.payment.application.port.out;

import java.util.Optional;

/**
 * 재고 캐시 outbound port — atomic decrement/rollback/조회/설정.
 * Phase 1.8(T1-08)에서 Redis Lua atomic 어댑터로 구현된다.
 */
public interface StockCachePort {

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
     * 재고 캐시 수량 설정 (warmup·대조 보정용).
     *
     * @param productId 상품 ID
     * @param quantity  설정 수량
     */
    void set(Long productId, int quantity);
}
