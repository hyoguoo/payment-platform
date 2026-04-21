package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.event;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import java.math.BigDecimal;

/**
 * payment.commands.confirm 토픽 payload.
 * OutboxRelayService가 Kafka로 발행하는 confirm 명령 메시지.
 * ADR-04: Transactional Outbox publisher 계층 — payload 최소 구현.
 *
 * @param orderId     주문 ID (파티션 키로도 사용)
 * @param paymentKey  결제 키 (PG 승인 시 필요)
 * @param totalAmount 결제 총액
 * @param gatewayType PG 유형
 * @param buyerId     구매자 ID
 */
public record PaymentConfirmCommandMessage(
        String orderId,
        String paymentKey,
        BigDecimal totalAmount,
        PaymentGatewayType gatewayType,
        Long buyerId
) {

}
