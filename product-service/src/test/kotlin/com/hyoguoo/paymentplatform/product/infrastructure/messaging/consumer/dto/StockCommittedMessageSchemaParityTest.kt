package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.reflect.full.primaryConstructor

/**
 * Schema parity 테스트 — StockCommittedMessage 필드 검증.
 *
 * consumer(product-service `StockCommittedMessage`)는
 * producer(payment-service `StockCommittedEvent`)와 동일 필드 구조를 유지해야 한다.
 * 공통 jar 금지 정책에 따라 두 서비스가 따로 들고 있는 타입의 필드 목록·순서·타입 동기화를
 * 이 테스트가 강제한다. 필드는 주 생성자 파라미터의 선언 순서로 읽는다.
 *
 * orderId 타입은 String 으로 통일 (producer/consumer 양쪽).
 */
@DisplayName("StockCommittedMessage schema parity")
class StockCommittedMessageSchemaParityTest {
    // -----------------------------------------------------------------------
    // StockCommittedMessage 필드 이름 set이 canonical과 동일해야 한다
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("StockCommittedMessage 필드 이름 set이 canonical(6개)과 동일해야 한다")
    fun stockCommittedMessage_fieldNameSet_matchesCanonical() {
        val actualNames = fieldNames()

        assertThat(actualNames)
            .`as`(
                "StockCommittedMessage 필드 이름 set이 canonical과 달라졌습니다. " +
                    "실제=$actualNames 기대=$CANONICAL_FIELD_ORDER",
            ).containsExactlyInAnyOrderElementsOf(CANONICAL_FIELD_ORDER)
    }

    // -----------------------------------------------------------------------
    // StockCommittedMessage 필드 순서가 canonical과 동일해야 한다
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("StockCommittedMessage 필드 선언 순서가 canonical과 동일해야 한다")
    fun stockCommittedMessage_fieldOrder_matchesCanonical() {
        val actualNames = fieldNames()

        assertThat(actualNames)
            .`as`("StockCommittedMessage 필드 순서가 canonical($CANONICAL_FIELD_ORDER)과 다릅니다. 실제=$actualNames")
            .isEqualTo(CANONICAL_FIELD_ORDER)
    }

    // -----------------------------------------------------------------------
    // StockCommittedMessage.orderId 타입이 String 이어야 한다 (Long 사용 금지)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("StockCommittedMessage.orderId 타입이 String이어야 한다")
    fun stockCommittedMessage_orderIdType_isString() {
        val orderIdType =
            parameters()
                .firstOrNull { it.name == "orderId" }
                ?.type
                ?.classifier
                ?: throw AssertionError("orderId 필드가 StockCommittedMessage에 없습니다")

        assertThat(orderIdType)
            .`as`("StockCommittedMessage.orderId 타입이 String 이어야 한다 — Long 사용 금지")
            .isEqualTo(String::class)
    }

    private fun parameters() = StockCommittedMessage::class.primaryConstructor!!.parameters

    private fun fieldNames(): List<String?> = parameters().map { it.name }

    private companion object {
        /**
         * 양쪽 타입이 보유해야 하는 정식 필드 목록·순서.
         * payment-service StockCommittedEvent 와 동일해야 한다.
         */
        val CANONICAL_FIELD_ORDER =
            listOf(
                "productId",
                "qty",
                "idempotencyKey",
                "occurredAt",
                "orderId",
                "expiresAt",
            )
    }
}
