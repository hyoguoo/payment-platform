package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hyoguoo.paymentplatform.payment.core.common.metrics.PaymentEosCommitFailureMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.transaction.KafkaTransactionManager;

@DisplayName("KafkaConsumerConfig — 결과 소비 리스너 컨테이너 동시성")
class KafkaConsumerConfigTest {

    private static final String CONCURRENCY_PROPERTY = "payment.kafka.events-confirmed.consumer.concurrency";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092")
            .withBean(ConsumerFactory.class, () -> mock(ConsumerFactory.class))
            .withBean(KafkaTransactionManager.class, () -> mock(KafkaTransactionManager.class))
            .withBean(CommonErrorHandler.class, () -> mock(CommonErrorHandler.class))
            .withBean(RecordMessageConverter.class, () -> mock(RecordMessageConverter.class))
            .withBean(DeadLetterPublishingRecoverer.class, () -> mock(DeadLetterPublishingRecoverer.class))
            .withBean(PaymentEosCommitFailureMetrics.class, () -> mock(PaymentEosCommitFailureMetrics.class))
            .withUserConfiguration(KafkaConsumerConfig.class);

    @Test
    @DisplayName("동시성 프로퍼티가 없으면 결과 토픽 파티션 수와 같은 3으로 컨테이너를 만든다")
    void 기본_동시성은_파티션_수와_같은_3() {
        contextRunner.run(context -> assertThat(createdContainerConcurrency(context.getBean(
                ConcurrentKafkaListenerContainerFactory.class))).isEqualTo(3));
    }

    @Test
    @DisplayName("동시성 프로퍼티를 지정하면 그 값으로 컨테이너를 만든다")
    void 프로퍼티로_동시성을_바꿀_수_있다() {
        contextRunner.withPropertyValues(CONCURRENCY_PROPERTY + "=1")
                .run(context -> assertThat(createdContainerConcurrency(context.getBean(
                        ConcurrentKafkaListenerContainerFactory.class))).isEqualTo(1));
    }

    private static int createdContainerConcurrency(ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
        ConcurrentMessageListenerContainer<?, ?> container = factory.createContainer("payment.events.confirmed");
        return container.getConcurrency();
    }
}
