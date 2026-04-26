package com.hyoguoo.paymentplatform.product.application.port.out;

/**
 * payment-side 재고 캐시에 대한 쓰기 포트(기술 중립).
 *
 * <p>StockCommitConsumer 가 이 포트를 통해 payment-service 재고 캐시를 갱신하며 실제 구현은 Redis 어댑터다.
 * 포트 이름에 기술 구현(Redis 등)을 노출하지 않는 기술 중립 원칙을 따른다.
 */
public interface PaymentStockCachePort {

    /**
     * 특정 상품의 재고를 payment-side 캐시에 SET한다.
     * RDB UPDATE 성공 이후에만 호출해야 한다 — RDB 실패 시 미호출이 불변식이다.
     *
     * @param productId 상품 식별자
     * @param stock     현재 재고 수량
     */
    void setStock(long productId, int stock);
}
