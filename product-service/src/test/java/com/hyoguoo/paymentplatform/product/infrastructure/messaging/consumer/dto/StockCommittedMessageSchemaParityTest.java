package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer.dto;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema parity 테스트 — StockCommittedMessage 필드 검증.
 *
 * <p>consumer(product-service {@code StockCommittedMessage})는
 * producer(payment-service {@code StockCommittedEvent})와 동일 필드 구조를 유지해야 한다.
 * 공통 jar 금지 정책에 따라 두 서비스가 따로 들고 있는 record 의 필드 목록·순서·타입 동기화를
 * 이 테스트가 강제한다.
 *
 * <p>orderId 타입은 String 으로 통일 (producer/consumer 양쪽).
 */
@DisplayName("StockCommittedMessage schema parity")
class StockCommittedMessageSchemaParityTest {

    /**
     * 양쪽 record 가 보유해야 하는 정식 필드 목록·순서.
     * payment-service StockCommittedEvent 와 동일해야 한다.
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
    // TC1: StockCommittedMessage 필드 이름 set이 canonical과 동일해야 한다
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("StockCommittedMessage 필드 이름 set이 canonical(6개)과 동일해야 한다")
    void stockCommittedMessage_fieldNameSet_matchesCanonical() {
        List<String> actualNames = Arrays.stream(
                        StockCommittedMessage.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(actualNames)
                .as("StockCommittedMessage 필드 이름 set이 canonical과 달라졌습니다. "
                        + "실제=" + actualNames + " 기대=" + CANONICAL_FIELD_ORDER)
                .containsExactlyInAnyOrderElementsOf(CANONICAL_FIELD_ORDER);
    }

    // -----------------------------------------------------------------------
    // TC2: StockCommittedMessage 필드 순서가 canonical과 동일해야 한다
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("StockCommittedMessage 필드 선언 순서가 canonical과 동일해야 한다")
    void stockCommittedMessage_fieldOrder_matchesCanonical() {
        List<String> actualNames = Arrays.stream(
                        StockCommittedMessage.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(actualNames)
                .as("StockCommittedMessage 필드 순서가 canonical(" + CANONICAL_FIELD_ORDER + ")과 다릅니다. "
                        + "실제=" + actualNames)
                .isEqualTo(CANONICAL_FIELD_ORDER);
    }

    // -----------------------------------------------------------------------
    // TC3: StockCommittedMessage.orderId 타입이 String 이어야 한다 (Long 사용 금지)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("StockCommittedMessage.orderId 타입이 String이어야 한다")
    void stockCommittedMessage_orderIdType_isString() {
        Class<?> orderIdType = Arrays.stream(StockCommittedMessage.class.getRecordComponents())
                .filter(rc -> "orderId".equals(rc.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("orderId 필드가 StockCommittedMessage에 없습니다"))
                .getType();

        assertThat(orderIdType)
                .as("StockCommittedMessage.orderId 타입이 String 이어야 한다 — Long 사용 금지")
                .isEqualTo(String.class);
    }
}
