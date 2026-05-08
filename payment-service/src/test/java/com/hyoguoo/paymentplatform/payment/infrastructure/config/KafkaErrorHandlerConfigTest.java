package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.classify.BinaryExceptionClassifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.test.util.ReflectionTestUtils;
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
    @DisplayName("errorHandler_빈_생성_성공 — Mock KafkaTemplate 주입 시 DefaultErrorHandler 반환")
    void errorHandler_빈_생성_성공() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> mockTemplate = mock(KafkaTemplate.class);

        DefaultErrorHandler handler = config.kafkaErrorHandler(mockTemplate);

        assertThat(handler).isNotNull();
    }

    @Test
    @DisplayName("not_retryable_예외_목록_포함_확인 — MessageConversionException / IllegalArgumentException / IllegalStateException 는 false(즉시 DLQ)")
    void not_retryable_예외_목록_포함_확인() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> mockTemplate = mock(KafkaTemplate.class);
        DefaultErrorHandler handler = config.kafkaErrorHandler(mockTemplate);

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
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> mockTemplate = mock(KafkaTemplate.class);
        DefaultErrorHandler handler = config.kafkaErrorHandler(mockTemplate);

        FixedBackOff backOff = (FixedBackOff) ReflectionTestUtils.getField(handler, "backOff");

        assertThat(backOff).isNotNull();
        BackOffExecution exec = backOff.start();
        assertThat(exec.nextBackOff()).isEqualTo(1000L);
        assertThat(exec.nextBackOff()).isEqualTo(1000L);
        assertThat(exec.nextBackOff()).isEqualTo(1000L);
        assertThat(exec.nextBackOff()).isEqualTo(1000L);
        assertThat(exec.nextBackOff()).isEqualTo(1000L);
        assertThat(exec.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }
}
