package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.ProductCatalogPageInfo;

/**
 * product-service 상품 목록(재고 화면) 조회 아웃바운드 포트 — 관리자 조회 전용.
 *
 * <p>결제 승인 경로가 쓰는 {@link ProductPort} 와 별개다.
 * 승인 경로 포트에 관리자 조회 용도가 섞이면 나중에 떼어내기 어렵다.
 */
public interface ProductCatalogQueryPort {

    ProductCatalogPageInfo getPage(int page, int size);
}
