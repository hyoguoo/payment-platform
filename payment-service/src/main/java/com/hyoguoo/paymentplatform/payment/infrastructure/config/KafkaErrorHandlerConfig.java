package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Spring Kafka DefaultErrorHandler bean 설정.
 *
 * <p>application 코드는 RuntimeException 만 throw 한다.
 * retry / DLQ 발행 책임은 이 bean 이 모두 흡수한다.
 *
 * <p>FixedBackOff(interval, maxAttempts) 로 1초 간격 5회 retry.
 * 5회 소진 후 DeadLetterPublishingRecoverer 로 DLQ 토픽(events.confirmed.dlq) 에 발행.
 *
 * <p>not-retryable 화이트리스트:
 * <ul>
 *   <li>{@link MessageConversionException} — 역직렬화 실패, 재시도 무의미</li>
 *   <li>{@link IllegalArgumentException} — 데이터 형식 손상, 재시도 무의미</li>
 *   <li>{@link IllegalStateException} — 불변식 위반, 재시도 무의미</li>
 * </ul>
 * 위 예외는 retry 없이 즉시 DLQ 로 발행된다.
 *
 * <p>wiring: Spring Boot Kafka 오토컨피그는 {@link org.springframework.kafka.listener.CommonErrorHandler}
 * 빈을 감지해 {@code kafkaListenerContainerFactory} 에 자동 주입한다.
 * 별도 {@code setCommonErrorHandler()} 호출 없이 이 bean 선언만으로 모든 @KafkaListener 에 적용된다.
 */
@Configuration
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class KafkaErrorHandlerConfig {

    @Value("${payment.kafka.error-handler.backoff.interval:1000}")
    private long backoffInterval;

    @Value("${payment.kafka.error-handler.backoff.max-attempts:5}")
    private long maxAttempts;

    /**
     * Kafka 컨슈머 에러 핸들러.
     * confirmedDlqKafkaTemplate 는 SCR-7 에서 보존된 빈을 재사용한다.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, String> confirmedDlqKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(confirmedDlqKafkaTemplate);
        FixedBackOff backOff = new FixedBackOff(backoffInterval, maxAttempts);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                MessageConversionException.class,
                IllegalArgumentException.class,
                IllegalStateException.class
        );
        return handler;
    }
}
