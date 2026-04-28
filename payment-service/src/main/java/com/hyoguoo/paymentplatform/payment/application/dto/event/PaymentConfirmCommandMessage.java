package com.hyoguoo.paymentplatform.payment.application.dto.event;

import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import java.math.BigDecimal;

/**
 * payment.commands.confirm 토픽 payload (wire contract).
 * OutboxRelayService가 Kafka로 발행하는 confirm 명령 메시지.
 *
 * <p>필드 구성은 pg-service consumer({@code PgConfirmCommand}) 와 1:1 정렬된다 — 두 서비스는
 * 공통 jar 를 공유하지 않으므로 JSON 필드명 기준으로 매핑된다.
 *
 * <p>application 계층이 infrastructure 패키지를 직접 참조하지 않도록 hexagonal layer 규약을 따른다.
 *
 * @param orderId    주문 ID (Kafka 파티션 키로도 사용)
 * @param paymentKey PG 승인 시 필요한 결제 키
 * @param amount     결제 총액
 * @param vendorType PG 벤더 (TOSS/NICEPAY)
 * @param eventUuid  consumer 측 eventUUID dedupe 키 (현재 구현: orderId 재사용 — confirm은 orderId당 1회만 발행)
 */
public record PaymentConfirmCommandMessage(
        String orderId,
        String paymentKey,
        BigDecimal amount,
        PaymentGatewayType vendorType,
        String eventUuid
) {

}
