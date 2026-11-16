package com.hyoguoo.paymentplatform.pg.infrastructure.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hyoguoo.paymentplatform.pg.application.messaging.PgTopics;
import com.hyoguoo.paymentplatform.pg.application.service.PgDlqService;
import com.hyoguoo.paymentplatform.pg.presentation.port.PgConfirmCommandService;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;

@DisplayName("pg-service 리스너 컨테이너 동시성")
class PgListenerConcurrencyTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092")
            .withBean(PgConfirmCommandService.class, () -> mock(PgConfirmCommandService.class))
            .withBean(PgDlqService.class, () -> mock(PgDlqService.class))
            .withUserConfiguration(KafkaListenerTestConfig.class,
                    PaymentConfirmConsumer.class, PaymentConfirmDlqConsumer.class);

    /**
     * DLQ 리스너의 1은 팩토리 기본값이다. 이 테스트는 DLQ 리스너의 {@code @KafkaListener} 에 자기 동시성이 없어
     * 명령 리스너 프로퍼티의 영향을 받지 않는다는 것까지 확인한다. 실제 앱에서 DLQ 리스너는 Boot 자동설정
     * 팩토리의 {@code spring.kafka.listener.concurrency}(기본 1)를 따른다.
     */
    @Test
    @DisplayName("결제 명령 리스너는 기본 동시성 3이고, DLQ 리스너는 그 값의 영향을 받지 않는다")
    void 명령_리스너만_기본_동시성_3() {
        contextRunner.run(context -> {
            assertThat(concurrencyOf(context, PgTopics.COMMANDS_CONFIRM)).isEqualTo(3);
            assertThat(concurrencyOf(context, PgTopics.COMMANDS_CONFIRM_DLQ)).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("결제 명령 리스너 동시성은 프로퍼티로 바꿀 수 있다")
    void 명령_리스너_동시성은_프로퍼티로_바꿀_수_있다() {
        contextRunner.withPropertyValues("pg.kafka.commands-confirm.consumer.concurrency=1")
                .run(context -> assertThat(concurrencyOf(context, PgTopics.COMMANDS_CONFIRM)).isEqualTo(1));
    }

    private static int concurrencyOf(ApplicationContext context, String topic) {
        KafkaListenerEndpointRegistry registry = context.getBean(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = registry.getListenerContainers().stream()
                .filter(c -> Arrays.asList(c.getContainerProperties().getTopics()).contains(topic))
                .findFirst()
                .orElseThrow(() -> new AssertionError("topic 리스너 컨테이너 없음: " + topic));
        return ((ConcurrentMessageListenerContainer<?, ?>) container).getConcurrency();
    }

    @Configuration
    @EnableKafka
    static class KafkaListenerTestConfig {

        @Bean
        @SuppressWarnings("unchecked")
        ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
            ConcurrentKafkaListenerContainerFactory<String, String> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(mock(ConsumerFactory.class));
            factory.setAutoStartup(false);
            return factory;
        }
    }
}
