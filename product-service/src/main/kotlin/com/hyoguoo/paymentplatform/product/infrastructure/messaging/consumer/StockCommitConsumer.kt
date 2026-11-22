package com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer

import com.hyoguoo.paymentplatform.product.application.usecase.StockCommitUseCase
import com.hyoguoo.paymentplatform.product.core.common.log.EventType
import com.hyoguoo.paymentplatform.product.core.common.log.LogDomain
import com.hyoguoo.paymentplatform.product.core.common.log.LogFmt
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.ProductTopics
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.consumer.dto.StockCommittedMessage
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * payment.events.stock-committed 토픽 Kafka consumer.
 * 메시지를 파싱하여 StockCommitUseCase 로 위임한다 — 멱등성·dedupe 로직은 use case 계층 책임.
 *
 * infra `@ConditionalOnProperty` 는 matchIfMissing=false(기본) — spring.kafka.bootstrap-servers
 * 미명시 시 빈 자체가 등록되지 않는다. 테스트 컨텍스트는 spring.kafka.listener.auto-startup=false 로 제어한다.
 *
 * groupId 는 `product-service-stock-commit` 으로 고정 — 다른 consumer 그룹과 격리한다.
 *
 * 레이어 규칙: `@KafkaListener` 는 infrastructure/messaging/consumer 에만 위치한다.
 *
 * 리스너는 동기 함수다. 같은 상품의 확정은 한 파티션의 컨슈머 스레드 하나가 순서대로 처리한다는 전제를
 * StockCommitUseCase 의 잠금 없는 차감이 쓴다. suspend 함수로 바꾸면 이 전제를 다시 증명해야 한다.
 */
@Component
@ConditionalOnProperty(name = ["spring.kafka.bootstrap-servers"])
class StockCommitConsumer(
    private val clock: Clock,
    private val stockCommitUseCase: StockCommitUseCase,
) {
    /**
     * payment.events.stock-committed 메시지를 수신하여 재고 확정 커밋을 실행한다.
     *
     * expiresAt 이 null 이면 `occurredAt + DEDUPE_TTL` 로 계산하고,
     * occurredAt 도 null 이면 `clock.instant() + DEDUPE_TTL` 로 fallback 한다 —
     * producer 가 expiresAt 을 미전송하던 구버전 페이로드와의 하위 호환 유지 목적이다.
     *
     * orderId 는 String 으로 통일됐고 producer 가 직접 채워 전송한다.
     * null 인 경우엔 빈 문자열로 fallback 하여 구버전 producer 대응만 남긴다.
     *
     * 파라미터는 non-null 이다. 값이 null 인 레코드는 Spring 이 본문 필수 검사로 거부해 격리된다.
     * nullable 로 바꾸면 null 이 그대로 넘어와 처리 결과가 바뀐다.
     *
     * 필드 누락·null 처리는 이관 전 자바 동작과 같게 둔다.
     * - productId 가 비면 `!!` 의 NPE 로 실패한다. NPE 는 재시도 대상이라 재시도 뒤 격리된다.
     *   `requireNotNull`(IllegalArgumentException)은 재시도 없이 격리돼 결과가 달라지므로 쓰지 않는다.
     * - qty 가 비면 0 으로 처리한다(자바 record 의 원시 int 에 Jackson 이 0 을 넣던 동작).
     */
    @KafkaListener(
        topics = [TOPIC],
        groupId = GROUP_ID,
        containerFactory = "kafkaListenerContainerFactory",
    )
    fun consume(message: StockCommittedMessage) {
        LogFmt.info(log, LogDomain.STOCK, EventType.STOCK_COMMIT_RECEIVED) {
            "productId=${message.productId} qty=${message.qty} eventUUID=${message.idempotencyKey}"
        }

        // 단일 진입점 단일 시각 산출: now를 먼저 산출하고 commit 인자와 resolveExpiresAt fallback base에 동일하게 전달한다.
        val now = clock.instant()
        val expiresAt = resolveExpiresAt(message, now)
        val orderId = message.orderId ?: ""

        stockCommitUseCase.commit(
            message.idempotencyKey,
            orderId,
            message.productId!!,
            message.qty ?: 0,
            now,
            expiresAt,
        )
    }

    /**
     * expiresAt null fallback 계산.
     *
     * `now` 는 consume 진입 시 단일 산출된 Instant — 내부에서 clock.instant() 를 재호출하지 않는다.
     * 1. message.expiresAt non-null → 그대로 사용
     * 2. null + occurredAt non-null → occurredAt + DEDUPE_TTL
     * 3. null + occurredAt null → now + DEDUPE_TTL (commit 인자 now 와 동일 Instant 공유)
     */
    private fun resolveExpiresAt(
        message: StockCommittedMessage,
        now: Instant,
    ): Instant {
        message.expiresAt?.let { return it }
        val base = message.occurredAt ?: now
        LogFmt.info(log, LogDomain.STOCK, EventType.STOCK_COMMIT_RECEIVED) {
            "expiresAt null fallback: base=$base eventUUID=${message.idempotencyKey}"
        }
        return base.plus(StockCommitUseCase.DEDUPE_TTL)
    }

    private companion object {
        const val TOPIC = ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED
        const val GROUP_ID = "product-service-stock-commit"

        val log = LoggerFactory.getLogger(StockCommitConsumer::class.java)
    }
}
