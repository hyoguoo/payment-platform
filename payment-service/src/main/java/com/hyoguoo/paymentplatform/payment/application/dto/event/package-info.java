/**
 * Kafka wire format DTO 패키지.
 *
 * <p>이 패키지에는 Kafka 메시지 직렬화·역직렬화에 사용되는 <b>외부 wire format</b> record만 배치한다.
 * 서비스 경계를 넘어 Kafka 토픽을 통해 다른 서비스와 교환되는 데이터 구조다.
 *
 * <p>예:
 * <ul>
 *   <li>{@code ConfirmedEventMessage} — pg-service에서 수신하는 결제 확정 결과 메시지</li>
 *   <li>{@code StockCommittedEvent} — product-service로 발행하는 재고 커밋 요청</li>
 *   <li>{@code StockRestoreEvent} — product-service로 발행하는 재고 복원 요청</li>
 *   <li>{@code PaymentConfirmCommandMessage} — pg-service로 발행하는 결제 확정 명령</li>
 *   <li>{@code StockSnapshotEvent} — product-service에서 수신하는 재고 스냅샷</li>
 * </ul>
 *
 * <p>JVM 내부 Spring ApplicationEvent는 {@code application.event} 패키지에 배치한다.
 *
 * @see com.hyoguoo.paymentplatform.payment.application.event
 */
package com.hyoguoo.paymentplatform.payment.application.dto.event;
