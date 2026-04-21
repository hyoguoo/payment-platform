package com.hyoguoo.paymentplatform.pg.infrastructure.config;

import com.hyoguoo.paymentplatform.pg.infrastructure.messaging.PgTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * pg-service 전용 Kafka 토픽 선언.
 * ADR-30: 파티션 수 3 — create-topics.sh와 동일하게 유지.
 * ADR-30(공통 jar 금지): payment-service의 KafkaTopicConfig를 참조하지 않고 독립 선언.
 *
 * <p>로컬/프로덕션 Compose 환경에서는 auto.create.topics.enable=false 이므로
 * create-topics.sh가 실제 토픽 생성을 담당한다.
 * 이 설정은 선언적 문서 + 테스트/임베디드 환경용이다.
 */
@Configuration
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class KafkaTopicConfig {

    private static final int PARTITIONS = 3;
    private static final int REPLICAS = 1;

    @Bean
    public NewTopic paymentCommandsConfirm() {
        return TopicBuilder.name(PgTopics.COMMANDS_CONFIRM)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    public NewTopic paymentCommandsConfirmDlq() {
        return TopicBuilder.name(PgTopics.COMMANDS_CONFIRM_DLQ)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    public NewTopic paymentEventsConfirmed() {
        return TopicBuilder.name(PgTopics.EVENTS_CONFIRMED)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }
}
