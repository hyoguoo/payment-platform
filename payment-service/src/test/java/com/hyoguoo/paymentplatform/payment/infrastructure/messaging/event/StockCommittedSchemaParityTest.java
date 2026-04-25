package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.event;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K3 schema parity 테스트 — StockCommittedEvent 필드 검증.
 *
 * <p>producer(payment-service {@code StockCommittedEvent})와
 * consumer(product-service {@code StockCommittedMessage})는 동일한 필드를 가져야 한다.
 * ADR-30: 공유 JAR 없이 독립 복제 — 필드 목록·순서 동기화를 테스트로 강제한다.
 *
 * <p>canonical 순서: {@code productId, qty, idempotencyKey, occurredAt, orderId, expiresAt}
 * (consumer 측 6 필드가 기준 — K3 스펙: producer를 consumer 수준으로 확장)
 */
@DisplayName("StockCommittedEvent schema parity")
class StockCommittedSchemaParityTest {

    /**
     * 양쪽 record 가 보유해야 하는 정식 필드 목록.
     * consumer(product-service StockCommittedMessage) 가 이미 보유한 6개 필드가 canonical.
     * producer(payment-service StockCommittedEvent) 도 동일 6개 필드를 갖춰야 한다.
     */
    private static final List<String> CANONICAL_FIELD_ORDER = List.of(
            "productId",
            "qty",
            "idempotencyKey",
            "occurredAt",
            "orderId",
            "expiresAt"
    );

    // -----------------------------------------------------------------------
    // TC1: StockCommittedEvent 필드 이름 set이 canonical과 동일해야 한다
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("StockCommittedEvent 필드 이름 set이 canonical(6개)과 동일해야 한다")
    void stockCommittedEvent_fieldNameSet_matchesCanonical() {
        List<String> actualNames = Arrays.stream(
                        StockCommittedEvent.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(actualNames)
                .as("StockCommittedEvent 필드 이름 set이 canonical과 달라졌습니다. "
                        + "실제=" + actualNames + " 기대=" + CANONICAL_FIELD_ORDER)
                .containsExactlyInAnyOrderElementsOf(CANONICAL_FIELD_ORDER);
    }

    // -----------------------------------------------------------------------
    // TC2: StockCommittedEvent 필드 순서가 canonical과 동일해야 한다
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("StockCommittedEvent 필드 선언 순서가 canonical과 동일해야 한다")
    void stockCommittedEvent_fieldOrder_matchesCanonical() {
        List<String> actualNames = Arrays.stream(
                        StockCommittedEvent.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(actualNames)
                .as("StockCommittedEvent 필드 순서가 canonical(" + CANONICAL_FIELD_ORDER + ")과 다릅니다. "
                        + "실제=" + actualNames)
                .isEqualTo(CANONICAL_FIELD_ORDER);
    }
}
