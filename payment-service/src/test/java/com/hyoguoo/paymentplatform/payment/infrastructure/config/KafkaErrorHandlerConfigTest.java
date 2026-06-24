package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import java.util.function.BiFunction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.classify.BinaryExceptionClassifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

@DisplayName("KafkaErrorHandlerConfig — DefaultErrorHandler bean 생성 및 설정 검증")
class KafkaErrorHandlerConfigTest {

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
    @DisplayName("not_retryable_예외_목록_포함_확인 — MessageConversionException / IllegalArgumentException / IllegalStateException 는 false(즉시 DLQ)")
    void not_retryable_예외_목록_포함_확인() {
        DefaultErrorHandler handler = config.kafkaErrorHandler(buildRecoverer());

        BinaryExceptionClassifier classifier =
                (BinaryExceptionClassifier) ReflectionTestUtils.invokeMethod(handler, "getClassifier");

        assertThat(classifier).isNotNull();
        // classify() returns false → not retryable (즉시 DLQ)
        assertThat(classifier.classify(new MessageConversionException("test"))).isFalse();
        assertThat(classifier.classify(new IllegalArgumentException("test"))).isFalse();
        assertThat(classifier.classify(new IllegalStateException("test"))).isFalse();
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
    @DisplayName("dlq_destination_resolver_정합 — payment.events.confirmed 처리 실패 시 발행 목적지가 EVENTS_CONFIRMED_DLQ(.dlq)임을 검증")
    @SuppressWarnings("unchecked")
    void dlq_destination_resolver_정합() {
        DeadLetterPublishingRecoverer recoverer = buildRecoverer();
        DefaultErrorHandler handler = config.kafkaErrorHandler(recoverer);

        // failureTracker → recoverer(DeadLetterPublishingRecoverer) → destinationResolver 추출
        // FailedRecordTracker 는 package-private → Object 로 수령 후 reflection 경유
        Object failureTracker = ReflectionTestUtils.getField(handler, "failureTracker");
        assertThat(failureTracker).isNotNull();
        ConsumerAwareRecordRecoverer rawRecoverer =
                (ConsumerAwareRecordRecoverer) ReflectionTestUtils.invokeMethod(failureTracker, "getRecoverer");
        assertThat(rawRecoverer).isInstanceOf(DeadLetterPublishingRecoverer.class);
        DeadLetterPublishingRecoverer resolvedRecoverer = (DeadLetterPublishingRecoverer) rawRecoverer;
        BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver =
                (BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition>)
                        ReflectionTestUtils.getField(resolvedRecoverer, "destinationResolver");
        assertThat(destinationResolver).isNotNull();

        // payment.events.confirmed 파티션 0 에서 처리 예외 발생 시 resolver 호출 결과 캡처
        ConsumerRecord<String, String> record = mock(ConsumerRecord.class);
        given(record.topic()).willReturn(PaymentTopics.EVENTS_CONFIRMED);
        given(record.partition()).willReturn(0);
        Exception exception = new RuntimeException("처리 실패 — retry 소진");

        TopicPartition destination = destinationResolver.apply(record, exception);

        // 목적지 토픽은 반드시 .dlq suffix (기본 resolver -dlt 가 아님)
        assertThat(destination.topic()).isEqualTo(PaymentTopics.EVENTS_CONFIRMED_DLQ);
        assertThat(destination.partition()).isEqualTo(0);
    }

    @Test
    @DisplayName("deadLetterPublishingRecoverer_빈_생성_성공 — kafkaErrorHandler 와 afterRollbackProcessor 가"
            + " 공유하는 단일 recoverer 빈을 반환")
    void deadLetterPublishingRecoverer_빈_생성_성공() {
        DeadLetterPublishingRecoverer recoverer = buildRecoverer();

        assertThat(recoverer).isNotNull();
    }

    @SuppressWarnings("unchecked")
    private DeadLetterPublishingRecoverer buildRecoverer() {
        KafkaTemplate<String, String> mockTemplate = mock(KafkaTemplate.class);
        return config.deadLetterPublishingRecoverer(mockTemplate);
    }
}
