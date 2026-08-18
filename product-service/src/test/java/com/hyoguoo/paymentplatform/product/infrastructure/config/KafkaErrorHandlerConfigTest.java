package com.hyoguoo.paymentplatform.product.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.product.exception.ProductStockException;
import com.hyoguoo.paymentplatform.product.exception.common.ProductErrorCode;
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.ProductTopics;
import java.util.function.BiFunction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.classify.BinaryExceptionClassifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

@DisplayName("KafkaErrorHandlerConfig — DefaultErrorHandler bean 생성 및 설정 검증")
class KafkaErrorHandlerConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private KafkaErrorHandlerConfig config;

    @BeforeEach
    void setUp() {
        config = new KafkaErrorHandlerConfig();
        ReflectionTestUtils.setField(config, "backoffInterval", 1000L);
        ReflectionTestUtils.setField(config, "maxAttempts", 5L);
    }

    @Test
    @DisplayName("errorHandler_빈_생성_성공 — Mock DeadLetterPublishingRecoverer 주입 시 DefaultErrorHandler 반환")
    void errorHandler_빈_생성_성공() {
        DeadLetterPublishingRecoverer recoverer = buildRecoverer();

        DefaultErrorHandler handler = config.kafkaErrorHandler(recoverer);

        assertThat(handler).isNotNull();
    }

    @Test
    @DisplayName("not_retryable_예외_목록_포함_확인 — 음수 가드 예외(ProductStockException)는 재시도 없이 즉시 격리,"
            + " 재고 row 미존재(IllegalStateException)는 기존 재시도 경로를 그대로 탄다")
    void not_retryable_예외_목록_포함_확인() {
        DefaultErrorHandler handler = config.kafkaErrorHandler(buildRecoverer());

        BinaryExceptionClassifier classifier =
                (BinaryExceptionClassifier) ReflectionTestUtils.invokeMethod(handler, "getClassifier");

        assertThat(classifier).isNotNull();
        // classify() returns false → not retryable (즉시 격리)
        assertThat(classifier.classify(new MessageConversionException("test"))).isFalse();
        assertThat(classifier.classify(new IllegalArgumentException("test"))).isFalse();
        assertThat(classifier.classify(ProductStockException.of(ProductErrorCode.NOT_ENOUGH_STOCK))).isFalse();
        // 재고 row 미존재는 목록에 없음 — 재시도 대상(true)
        assertThat(classifier.classify(new IllegalStateException("재고 row 없음"))).isTrue();
        // 일반 RuntimeException 은 retryable (true)
        assertThat(classifier.classify(new RuntimeException("transient"))).isTrue();
    }

    @Test
    @DisplayName("backoff_설정값_반영 — interval=1000ms, maxAttempts=5 (6번째 호출에서 STOP)")
    void backoff_설정값_반영() {
        DefaultErrorHandler handler = config.kafkaErrorHandler(buildRecoverer());

        Object failureTracker = ReflectionTestUtils.getField(handler, "failureTracker");
        assertThat(failureTracker).isNotNull();
        BackOff backOff = (BackOff) ReflectionTestUtils.getField(failureTracker, "backOff");
        assertThat(backOff).isInstanceOf(FixedBackOff.class);

        BackOffExecution exec = backOff.start();
        assertThat(exec.nextBackOff()).isEqualTo(1000L);
        assertThat(exec.nextBackOff()).isEqualTo(1000L);
        assertThat(exec.nextBackOff()).isEqualTo(1000L);
        assertThat(exec.nextBackOff()).isEqualTo(1000L);
        assertThat(exec.nextBackOff()).isEqualTo(1000L);
        assertThat(exec.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    @DisplayName("dlq_destination_resolver_정합 — payment.events.stock-committed 처리 실패 시 발행 목적지가"
            + " PAYMENT_EVENTS_STOCK_COMMITTED_DLQ(.dlq)임을 검증")
    @SuppressWarnings("unchecked")
    void dlq_destination_resolver_정합() {
        DeadLetterPublishingRecoverer recoverer = buildRecoverer();
        DefaultErrorHandler handler = config.kafkaErrorHandler(recoverer);

        Object failureTracker = ReflectionTestUtils.getField(handler, "failureTracker");
        assertThat(failureTracker).isNotNull();
        Object rawRecoverer = ReflectionTestUtils.invokeMethod(failureTracker, "getRecoverer");
        assertThat(rawRecoverer).isInstanceOf(DeadLetterPublishingRecoverer.class);
        BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver =
                (BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition>)
                        ReflectionTestUtils.getField(rawRecoverer, "destinationResolver");
        assertThat(destinationResolver).isNotNull();

        ConsumerRecord<String, String> record = mock(ConsumerRecord.class);
        org.mockito.BDDMockito.given(record.topic()).willReturn(ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED);
        org.mockito.BDDMockito.given(record.partition()).willReturn(0);
        Exception exception = new RuntimeException("처리 실패 — retry 소진");

        TopicPartition destination = destinationResolver.apply(record, exception);

        assertThat(destination.topic()).isEqualTo(ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED_DLQ);
        assertThat(destination.partition()).isEqualTo(0);
    }

    @Test
    @DisplayName("quarantine_key는_orderId_productId_조합 — 원본 키가 productId 단독이어도 격리 메시지 키는"
            + " orderId:productId 로 재조합된다")
    void quarantine_key는_orderId_productId_조합() {
        String orderId = "order-777";
        long productId = 55L;
        String json = "{\"productId\":" + productId + ",\"qty\":3,\"idempotencyKey\":\"uuid-1\","
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"orderId\":\"" + orderId + "\","
                + "\"expiresAt\":\"2026-01-09T00:00:00Z\"}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED, 0, 10L, String.valueOf(productId), json);
        StockCommitQuarantineRecoverer recoverer = new StockCommitQuarantineRecoverer(
                mock(KafkaTemplate.class), objectMapper,
                (rec, ex) -> new TopicPartition(ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED_DLQ, rec.partition()));

        ProducerRecord<Object, Object> outRecord = (ProducerRecord<Object, Object>) ReflectionTestUtils.invokeMethod(
                recoverer, "createProducerRecord", record,
                new TopicPartition(ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED_DLQ, 0),
                new RecordHeaders(), null, null);

        assertThat(outRecord).isNotNull();
        assertThat(outRecord.key()).isEqualTo(orderId + ":" + productId);
    }

    @Test
    @DisplayName("quarantine_key_파싱실패시_원본키로_fallback — orderId 가 없는 페이로드는 원본 키를 그대로 쓴다")
    void quarantine_key_파싱실패시_원본키로_fallback() {
        String rawKey = "55";
        String malformedJson = "not-a-json";
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED, 0, 10L, rawKey, malformedJson);
        StockCommitQuarantineRecoverer recoverer = new StockCommitQuarantineRecoverer(
                mock(KafkaTemplate.class), objectMapper,
                (rec, ex) -> new TopicPartition(ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED_DLQ, rec.partition()));

        ProducerRecord<Object, Object> outRecord = (ProducerRecord<Object, Object>) ReflectionTestUtils.invokeMethod(
                recoverer, "createProducerRecord", record,
                new TopicPartition(ProductTopics.PAYMENT_EVENTS_STOCK_COMMITTED_DLQ, 0),
                new RecordHeaders(), null, null);

        assertThat(outRecord).isNotNull();
        assertThat(outRecord.key()).isEqualTo(rawKey);
    }

    @Test
    @DisplayName("deadLetterPublishingRecoverer_빈_생성_성공 — StockCommitQuarantineRecoverer 인스턴스를 반환")
    void deadLetterPublishingRecoverer_빈_생성_성공() {
        DeadLetterPublishingRecoverer recoverer = buildRecoverer();

        assertThat(recoverer).isInstanceOf(StockCommitQuarantineRecoverer.class);
    }

    @SuppressWarnings("unchecked")
    private DeadLetterPublishingRecoverer buildRecoverer() {
        KafkaTemplate<String, String> mockTemplate = mock(KafkaTemplate.class);
        return config.deadLetterPublishingRecoverer(mockTemplate, objectMapper);
    }
}
