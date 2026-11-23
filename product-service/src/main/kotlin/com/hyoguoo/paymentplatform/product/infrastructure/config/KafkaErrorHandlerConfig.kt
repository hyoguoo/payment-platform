package com.hyoguoo.paymentplatform.product.infrastructure.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.hyoguoo.paymentplatform.product.exception.ProductStockException
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.ProductTopics
import org.apache.kafka.common.TopicPartition
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.messaging.converter.MessageConversionException
import org.springframework.util.backoff.FixedBackOff
import java.util.function.BiFunction

/**
 * Spring Kafka DefaultErrorHandler bean 설정 — payment-service `KafkaErrorHandlerConfig` 패턴을 옮겨온다.
 *
 * application 코드는 RuntimeException 만 throw 한다. retry / 격리 토픽 발행 책임은 이 bean 이 모두 흡수한다.
 *
 * FixedBackOff(interval, maxAttempts) 로 재시도한 뒤 소진하면 [StockCommitQuarantineRecoverer]
 * (격리 메시지 키를 orderId:productId 조합으로 다시 만드는 [DeadLetterPublishingRecoverer] 특수화)로
 * [ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED_DLQ] 에 발행한다.
 *
 * not-retryable 화이트리스트:
 * - [MessageConversionException] — 역직렬화 실패, 재시도 무의미
 * - [IllegalArgumentException] — 데이터 형식 손상, 재시도 무의미
 * - [ProductStockException] — 재고 확정 음수 가드(잔량 부족). 재시도로 풀릴 조건이 아니라 대기열만 늘린다
 *
 * 위 예외는 retry 없이 즉시 격리 토픽으로 발행된다.
 *
 * **[IllegalStateException](재고 row 미존재)은 이 목록에 없다.**
 * `StockCommitUseCase.commitToRdb` 가 재고 row 미존재와 잔량 부족을 서로 다른 예외
 * 타입으로 분리해 둔 것이 이 목록을 구분해서 등재하기 위해서다 — 잔량 부족만 재시도 무의미로
 * 판정하고, row 미존재는(운영 데이터 정합 문제로 스캐폴딩이 늦게 따라붙는 경우 등) 기존 재시도
 * 경로를 그대로 탄다.
 *
 * 코틀린 표준 함수도 이 분류에 걸린다: `require`·`requireNotNull` 은 IllegalArgumentException(재시도 없음),
 * `check`·`error` 는 IllegalStateException(재시도), `!!` 는 NullPointerException(재시도).
 *
 * wiring: Spring Boot Kafka 오토컨피그는 `CommonErrorHandler` 빈을 감지해 `kafkaListenerContainerFactory` 에
 * 자동 주입한다. 별도 `setCommonErrorHandler()` 호출 없이 이 bean 선언만으로 `StockCommitConsumer` 에 적용된다.
 *
 * 설정값은 필드로 주입받는다. 단위 테스트가 설정 객체를 직접 만들고 이 필드에 값을 넣는다.
 */
@Configuration
@ConditionalOnProperty(name = ["spring.kafka.bootstrap-servers"])
class KafkaErrorHandlerConfig {
    @Value("\${product.kafka.error-handler.backoff.interval:1000}")
    private var backoffInterval: Long = 0

    @Value("\${product.kafka.error-handler.backoff.max-attempts:5}")
    private var maxAttempts: Long = 0

    /**
     * 격리 토픽 발행 recoverer. 목적지는 항상 [ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED_DLQ]
     * (원본 파티션 번호는 유지) — 원본 토픽이 하나뿐이라 destinationResolver 는 상수 매핑이면 충분하다.
     */
    @Bean
    fun deadLetterPublishingRecoverer(
        stockCommittedQuarantineKafkaTemplate: KafkaTemplate<String, String>,
        objectMapper: ObjectMapper,
    ): DeadLetterPublishingRecoverer =
        StockCommitQuarantineRecoverer(
            stockCommittedQuarantineKafkaTemplate,
            objectMapper,
            BiFunction { record, _ ->
                TopicPartition(ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED_DLQ, record.partition())
            },
        )

    /**
     * Kafka 컨슈머 에러 핸들러. [deadLetterPublishingRecoverer] 빈을 재사용한다.
     */
    @Bean
    fun kafkaErrorHandler(deadLetterPublishingRecoverer: DeadLetterPublishingRecoverer): DefaultErrorHandler {
        val handler = DefaultErrorHandler(deadLetterPublishingRecoverer, FixedBackOff(backoffInterval, maxAttempts))
        handler.addNotRetryableExceptions(
            MessageConversionException::class.java,
            IllegalArgumentException::class.java,
            ProductStockException::class.java,
        )
        return handler
    }
}
