package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConfirmedEventMessage Jackson 역직렬화 단위 테스트 —
 * amount(Long) / approvedAt(String ISO-8601) 필드 파싱을 검증한다.
 */
@DisplayName("ConfirmedEventMessage")
class ConfirmedEventMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // -----------------------------------------------------------------------
    // TC1: APPROVED 메시지 역직렬화 — amount/approvedAt 파싱
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("APPROVED JSON 역직렬화 시 amount/approvedAt 필드 파싱")
    void deserializeApprovedJson_parsesAmountAndApprovedAt() throws Exception {
        String json = """
                {
                  "orderId": "order-001",
                  "status": "APPROVED",
                  "reasonCode": null,
                  "eventUuid": "evt-uuid-001",
                  "amount": 15000,
                  "approvedAt": "2026-04-24T10:00:00+09:00"
                }
                """;

        ConfirmedEventMessage message = objectMapper.readValue(json, ConfirmedEventMessage.class);

        assertThat(message.orderId()).isEqualTo("order-001");
        assertThat(message.status()).isEqualTo("APPROVED");
        assertThat(message.amount()).isEqualTo(15000L);
        assertThat(message.approvedAt()).isEqualTo("2026-04-24T10:00:00+09:00");
    }

    // -----------------------------------------------------------------------
    // TC2: FAILED 메시지 역직렬화 — amount/approvedAt null 허용
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("FAILED JSON 역직렬화 시 amount/approvedAt null 허용")
    void deserializeFailedJson_amountAndApprovedAtAreNull() throws Exception {
        String json = """
                {
                  "orderId": "order-002",
                  "status": "FAILED",
                  "reasonCode": "CARD_DECLINED",
                  "eventUuid": "evt-uuid-002"
                }
                """;

        ConfirmedEventMessage message = objectMapper.readValue(json, ConfirmedEventMessage.class);

        assertThat(message.status()).isEqualTo("FAILED");
        assertThat(message.amount()).isNull();
        assertThat(message.approvedAt()).isNull();
    }
}
