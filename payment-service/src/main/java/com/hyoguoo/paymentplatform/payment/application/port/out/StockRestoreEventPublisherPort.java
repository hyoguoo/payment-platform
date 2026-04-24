package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.application.dto.StockRestoreEventPayload;

/**
 * stock.events.restore 토픽 발행 전용 고수준 port.
 * FAILED 결제 시 재고 복원 이벤트를 발행한다.
 * ADR-14: stock 도메인 경계 — 발행만 담당, 실제 재고 처리는 별도 서비스 책임.
 *
 * <p>T3-04b: FAILED 결제 stock.events.restore 보상 이벤트 발행 (UUID 멱등) 태스크에서
 * 실제 Kafka 어댑터로 교체 예정.
 *
 * <p>T-B2: qty=0 플레이스홀더 경로였던 publish(orderId, List<Long>) 오버로드 철거.
 * 호출처 전수 확인 결과 0건 — FailureCompensationService.compensate(orderId, productId, qty) 단일 경로만 사용.
 */
public interface StockRestoreEventPublisherPort {

    /**
     * 보상 이벤트 payload(UUID 포함)를 outbox에 발행한다.
     * ADR-16: eventUUID 결정론적 생성으로 멱등성 보장 — 동일 UUID 재호출 시 no-op.
     *
     * @param payload 재고 복원 이벤트 payload (eventUUID·orderId·productId·qty)
     */
    void publishPayload(StockRestoreEventPayload payload);
}
