package com.hyoguoo.paymentplatform.pg.infrastructure.messaging.event;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K3 schema parity 테스트 — ConfirmedEventPayload 필드 순서 검증.
 *
 * <p>pg-service {@code ConfirmedEventPayload} 가 정식 발행 측이다.
 * payment-service {@code ConfirmedEventMessage} 소비 측도 동일 순서를 유지해야 하며,
 * 이 테스트는 producer 측 순서가 canonical과 일치함을 보증한다.
 *
 * <p>canonical 순서: {@code orderId, status, reasonCode, amount, approvedAt, eventUuid}
 */
@DisplayName("ConfirmedEventPayload schema parity")
class ConfirmedEventPayloadSchemaParityTest {

    /**
     * pg-service ConfirmedEventPayload 의 정식 발행 순서.
     * 이 순서가 바뀌면 payment-service ConfirmedEventMessage 와 함께 갱신해야 한다.
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
    // TC1: ConfirmedEventPayload 필드 이름 set이 canonical과 동일해야 한다
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("ConfirmedEventPayload 필드 이름 set이 canonical과 동일해야 한다")
    void confirmedEventPayload_fieldNameSet_matchesCanonical() {
        List<String> actualNames = Arrays.stream(
                        ConfirmedEventPayload.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(actualNames)
                .as("ConfirmedEventPayload 필드 이름 set이 canonical과 달라졌습니다. 양쪽 동기화 필요")
                .containsExactlyInAnyOrderElementsOf(CANONICAL_FIELD_ORDER);
    }

    // -----------------------------------------------------------------------
    // TC2: ConfirmedEventPayload 필드 순서가 canonical과 동일해야 한다
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("ConfirmedEventPayload 필드 선언 순서가 canonical과 동일해야 한다")
    void confirmedEventPayload_fieldOrder_matchesCanonical() {
        List<String> actualNames = Arrays.stream(
                        ConfirmedEventPayload.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(actualNames)
                .as("ConfirmedEventPayload 필드 순서가 canonical(" + CANONICAL_FIELD_ORDER + ")과 다릅니다. "
                        + "실제=" + actualNames)
                .isEqualTo(CANONICAL_FIELD_ORDER);
    }

    // -----------------------------------------------------------------------
    // TC3: ConfirmedEventPayload 에 @JsonPropertyOrder 어노테이션이 있어야 한다
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("ConfirmedEventPayload 에 @JsonPropertyOrder 어노테이션이 있어야 한다")
    void confirmedEventPayload_hasJsonPropertyOrder() {
        JsonPropertyOrder annotation = ConfirmedEventPayload.class
                .getAnnotation(JsonPropertyOrder.class);

        assertThat(annotation)
                .as("ConfirmedEventPayload 에 @JsonPropertyOrder 어노테이션이 없습니다")
                .isNotNull();

        assertThat(List.of(annotation.value()))
                .as("@JsonPropertyOrder 값이 canonical 순서와 다릅니다")
                .isEqualTo(CANONICAL_FIELD_ORDER);
    }
}
