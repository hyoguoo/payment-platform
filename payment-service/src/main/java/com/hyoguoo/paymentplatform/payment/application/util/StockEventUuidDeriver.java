package com.hyoguoo.paymentplatform.payment.application.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * stock 이벤트 전용 결정론적 UUID v3 도출 유틸리티.
 * ADR-16: "{prefix}:{orderId}:{productId}" 시드 기반 UUID nameUUIDFromBytes.
 *
 * <p>사용처:
 * <ul>
 *   <li>{@code PaymentConfirmResultUseCase.buildStockCommitOutbox} — stock-commit prefix</li>
 *   <li>{@code FailureCompensationService.compensate} — stock-restore prefix</li>
 * </ul>
 *
 * <p>K1: multi-product 결제 시 모든 stock-committed 이벤트가 동일 orderId를 공유하더라도
 * productId 별로 고유한 idempotencyKey를 가지도록 보장한다.
 * product-service {@code StockCommitUseCase}는 이 키를 dedupe 키로 사용하므로
 * 첫 product 외 나머지가 skip되는 회귀를 차단한다.
 */
public final class StockEventUuidDeriver {

    private StockEventUuidDeriver() {
        // 유틸리티 클래스 — 인스턴스화 금지
    }

    /**
     * (orderId, productId, prefix) 기반 결정론적 UUID v3 도출.
     * 동일 입력 → 동일 UUID 출력 보장 (ADR-16 멱등성).
     *
     * @param orderId   주문 ID
     * @param productId 상품 ID
     * @param prefix    이벤트 구분 접두사 (예: "stock-commit", "stock-restore")
     * @return UUID v3 문자열 (소문자 하이픈 포함)
     */
    public static String derive(String orderId, Long productId, String prefix) {
        String seed = prefix + ":" + orderId + ":" + productId;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
