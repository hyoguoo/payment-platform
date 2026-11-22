package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer.dto

import java.time.Instant

/**
 * payment.events.stock-committed 토픽 페이로드 — product-service consumer 수신 DTO.
 *
 * 공통 jar 금지 정책에 따라 payment-service StockCommittedEvent 를 직접 참조하지 않고
 * product-service 자체 타입으로 복제 보유한다.
 *
 * orderId 는 producer(payment-service StockCommittedEvent) 와 타입을 맞춰 String 으로 둔다 —
 * 그래야 형 변환 충돌이 없다. expiresAt 은 producer 가 직접 채워 전송하며, consumer 의 fallback 은
 * 구버전 페이로드와의 하위 호환 용도로만 남긴다.
 *
 * 모든 필드를 nullable 로 둔다. 필드가 빠지거나 null 인 메시지도 역직렬화는 성공하고, 처리 결과는 consumer 가
 * 정한다(docs/topics/KOTLIN-PILOT-PRODUCT/DESIGN.md "확정 메시지 필드별 처리"). non-null 로 선언하면 코틀린
 * Jackson 모듈이 역직렬화를 실패시키고, 그 예외는 재시도 없이 격리되어 처리 결과가 바뀐다.
 *
 * 필드:
 * - productId — 재고 차감 대상 상품 ID
 * - qty — 확정 차감 수량
 * - idempotencyKey — 멱등성 키 (eventUUID로 사용)
 * - occurredAt — 이벤트 발생 시각
 * - orderId — 주문 ID (dedupe 메타데이터, String 통일)
 * - expiresAt — dedupe TTL 만료 시각 (producer가 채우고, null이면 consumer가 fallback 계산)
 */
data class StockCommittedMessage(
    val productId: Long?,
    val qty: Int?,
    val idempotencyKey: String?,
    val occurredAt: Instant?,
    val orderId: String?,
    val expiresAt: Instant?,
)
