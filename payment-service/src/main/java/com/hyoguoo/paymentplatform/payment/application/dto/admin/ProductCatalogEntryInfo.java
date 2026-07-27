package com.hyoguoo.paymentplatform.payment.application.dto.admin;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

/**
 * 재고 화면(관리자) 목록에 담기는 상품 한 건 — payment 쪽 도메인 표현.
 * product-service {@code ProductPage} 의 상품 항목을 payment 도메인 DTO 로 옮긴 것.
 *
 * <p>{@code confirmedStock} 은 product-service 가 상품과 재고를 조인해 계산한 확정 수량이다.
 * 실시간 판매 가능 여부(캐시 등)와는 다를 수 있다 — 화면 문구는 Task 12 에서 안내한다.
 */
@Getter
@Builder
public class ProductCatalogEntryInfo {

    private final Long id;
    private final String name;
    private final BigDecimal price;
    private final Integer confirmedStock;
    private final Long sellerId;
}
