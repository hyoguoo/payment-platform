package com.hyoguoo.paymentplatform.pg.application.port.out;

/**
 * pg-service outbound 포트 — 결제 이벤트 발행 추상.
 * ADR-04: Transactional Outbox 패턴을 통한 이벤트 발행.
 * 구현체(KafkaPgEventPublisher 등)는 T2a-05a에서 추가.
 *
 * <p>발행 대상 토픽: payment.events.confirmed (PgTopics.EVENTS_CONFIRMED 참고)
 */
public interface PgEventPublisherPort {

    /**
     * 결제 승인 완료 이벤트를 발행한다.
     *
     * @param orderId     주문 식별자
     * @param status      처리 결과 상태 문자열 (APPROVED / FAILED / QUARANTINED)
     * @param reasonCode  실패 사유 코드 (성공 시 null)
     * @param eventUuid   이벤트 멱등성 식별자
     */
    void publishConfirmed(String orderId, String status, String reasonCode, String eventUuid);
}
