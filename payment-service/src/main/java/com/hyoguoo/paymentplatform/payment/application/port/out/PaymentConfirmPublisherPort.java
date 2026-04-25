package com.hyoguoo.paymentplatform.payment.application.port.out;

import java.math.BigDecimal;

/**
 * TX 동기화 활성 스레드에서 호출되는 결제 확인 이벤트 발행 포트.
 *
 * <p><b>계약 (Port Contract)</b>:
 * <ul>
 *   <li>구현체는 in-memory 로 즉시 완주해야 한다 — 원격 I/O(Kafka 등)로 블로킹 금지.</li>
 *   <li>TX 내부 호출이므로 긴 지연은 DB 커넥션 점유 시간을 확대시켜 Hikari 풀 고갈로 이어진다.</li>
 *   <li>실제 Kafka 발행은 {@code @TransactionalEventListener(AFTER_COMMIT)} 리스너가 담당한다
 *       (예: {@code OutboxImmediateEventHandler}).</li>
 * </ul>
 *
 * <p>이 계약을 위반한 구현은 {@code OutboxImmediatePublisherTest} 의 non-blocking assertion 에서 실패한다.
 *
 * @see com.hyoguoo.paymentplatform.payment.infrastructure.messaging.publisher.OutboxImmediatePublisher
 */
public interface PaymentConfirmPublisherPort {

    void publish(String orderId, Long userId, BigDecimal amount, String paymentKey);
}
