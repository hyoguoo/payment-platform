package com.hyoguoo.paymentplatform.product.application.dto;

import com.hyoguoo.paymentplatform.product.domain.Product;
import java.util.List;

/**
 * 상품 목록 페이징 조회 결과.
 *
 * <p>product-service 내부 전용 DTO다 — payment-service 의 페이지 응답 DTO 를 공유하지 않는다
 * (서비스 간 공유 jar 금지 방침).
 *
 * @param content        현재 페이지에 담긴 상품(확정 재고 포함) 목록
 * @param page           0부터 시작하는 페이지 번호
 * @param size           요청한 페이지 크기
 * @param totalElements  조건에 맞는 전체 건수 (페이지 계산 근거)
 */
public record ProductPage(List<Product> content, int page, int size, long totalElements) {

}
