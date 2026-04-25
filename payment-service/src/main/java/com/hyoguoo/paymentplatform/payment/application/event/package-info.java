/**
 * Spring ApplicationEvent record 패키지.
 *
 * <p>이 패키지에는 {@link org.springframework.context.ApplicationEventPublisher}로 발행되는
 * <b>내부 이벤트</b> record만 배치한다. 서비스 경계를 넘지 않으며 JVM 내부에서만 소비된다.
 *
 * <p>예: {@link com.hyoguoo.paymentplatform.payment.application.event.StockOutboxReadyEvent} —
 * stock_outbox row DB 커밋 후 relay 트리거용 AFTER_COMMIT 이벤트.
 *
 * <p>외부 Kafka wire format DTO는 {@code application.dto.event} 패키지에 배치한다.
 *
 * @see com.hyoguoo.paymentplatform.payment.application.dto.event
 */
package com.hyoguoo.paymentplatform.payment.application.event;
