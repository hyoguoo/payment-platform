package com.hyoguoo.paymentplatform.payment.application.event;

/**
 * stock_outbox row가 DB에 삽입되고 트랜잭션이 커밋된 직후,
 * StockOutboxImmediateEventHandler가 수신하는 Spring ApplicationEvent.
 *
 * <p>stock 발행은 transactional outbox 패턴이다 — TX 내부에서 INSERT 후 이 이벤트만 발행하고,
 * AFTER_COMMIT 리스너가 비동기로 relay 를 트리거한다. payment-service 의 PaymentConfirmEvent
 * (payment_outbox 용) 와 동격 구조다. 필드는 outboxId 하나 — relay 메서드가 id 로 row 를 재조회한다.
 *
 * @param outboxId stock_outbox PK (AUTO_INCREMENT)
 */
public record StockOutboxReadyEvent(Long outboxId) {

}
