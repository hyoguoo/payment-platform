package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer.dto;

import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K3 schema parity 테스트 — ConfirmedEventMessage 필드 순서 검증.
 *
 * <p>pg-service {@code ConfirmedEventPayload} 가 Kafka로 발행하는 순서:
 * {@code orderId, status, reasonCode, amount, approvedAt, eventUuid}
 *
 * <p>payment-service {@code ConfirmedEventMessage} 소비 측도 동일 순서를 유지해야 한다.
 * ADR-30: 공유 JAR 없이 독립 복제 — 순서 동기화를 테스트로 강제한다.
 *
 * <p>Jackson은 필드명으로 역직렬화하므로 순서 불일치가 런타임 오류로 이어지지 않으나,
 * 새 필드 추가 시 한쪽 누락을 PR 통과 후 실기동 장애로 알게 되는 위험을 이 테스트가 잡는다.
 */
@DisplayName("ConfirmedEventMessage schema parity")
class ConfirmedEventSchemaParityTest {

    /**
     * pg-service ConfirmedEventPayload 의 정식 발행 순서.
     * producer 측 변경 시 이 목록도 함께 갱신한다.
     */
    private static final List<String> CANONICAL_FIELD_ORDER = List.of(
            "orderId",
            "status",
            "reasonCode",
            "amount",
            "approvedAt",
            "eventUuid"
    );

    // -----------------------------------------------------------------------
    // TC1: ConfirmedEventMessage 필드 이름 set이 canonical과 동일해야 한다
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("ConfirmedEventMessage 필드 이름 set이 pg-service canonical과 동일해야 한다")
    void confirmedEventMessage_fieldNameSet_matchesCanonical() {
        List<String> actualNames = Arrays.stream(
                        ConfirmedEventMessage.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(actualNames)
                .as("ConfirmedEventMessage 필드 이름 set이 canonical과 달라졌습니다. 양쪽 동기화 필요")
                .containsExactlyInAnyOrderElementsOf(CANONICAL_FIELD_ORDER);
    }

    // -----------------------------------------------------------------------
    // TC2: ConfirmedEventMessage 필드 순서가 canonical과 동일해야 한다
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("ConfirmedEventMessage 필드 선언 순서가 pg-service canonical과 동일해야 한다")
    void confirmedEventMessage_fieldOrder_matchesCanonical() {
        List<String> actualNames = Arrays.stream(
                        ConfirmedEventMessage.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(actualNames)
                .as("ConfirmedEventMessage 필드 순서가 canonical(" + CANONICAL_FIELD_ORDER + ")과 다릅니다. "
                        + "실제=" + actualNames)
                .isEqualTo(CANONICAL_FIELD_ORDER);
    }
}
