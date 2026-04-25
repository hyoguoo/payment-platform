package com.hyoguoo.paymentplatform.payment.application.event;

/**
 * stock_outbox row가 DB에 삽입되고 트랜잭션이 커밋된 직후,
 * StockOutboxImmediateEventHandler가 수신하는 Spring ApplicationEvent.
 *
 * <p>T-J1: payment-service stock 발행을 Transactional Outbox 패턴으로 전환.
 * TX 내부에서 INSERT → AFTER_COMMIT 리스너가 relay 트리거.
 *
 * <p>ADR-04 대칭: payment-service의 PaymentConfirmEvent(payment_outbox용)와 동격.
 * 필드는 outboxId 하나 — relay 메서드가 id 기반으로 row를 재조회한다.
 *
 * @param outboxId stock_outbox PK (AUTO_INCREMENT)
 */
public record StockOutboxReadyEvent(Long outboxId) {

}
