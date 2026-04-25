package com.hyoguoo.paymentplatform.payment.application.event;

import io.micrometer.context.ContextSnapshot;

/**
 * stock.events.commit 발행 요청 Spring ApplicationEvent.
 * ADR-04: TX 내부에서 발행 — 실제 Kafka 발행은 AFTER_COMMIT 리스너가 수행한다.
 *
 * <p>T-I4: AFTER_COMMIT 리스너 실행 시점에 active span이 이미 종료되어 새 trace가
 * 생성되는 회귀를 방지한다. producer 측에서 captureAll() 로 context를 캡처하고,
 * 리스너가 setThreadLocals()로 복원한 뒤 Kafka publish를 수행한다.
 *
 * @param eventUUID       멱등성 키 (ADR-16 결정론적 UUID)
 * @param orderId         주문 ID
 * @param productId       재고 차감 대상 상품 ID
 * @param quantity        차감 수량
 * @param idempotencyKey  멱등성 키 (주문 ID 등 — 소비자 측 중복 처리 식별용)
 * @param contextSnapshot producer 측 캡처 컨텍스트 (MDC + OTel span) — 리스너에서 복원
 */
public record StockCommitRequestedEvent(
        String eventUUID,
        String orderId,
        Long productId,
        int quantity,
        String idempotencyKey,
        ContextSnapshot contextSnapshot
) {

}
