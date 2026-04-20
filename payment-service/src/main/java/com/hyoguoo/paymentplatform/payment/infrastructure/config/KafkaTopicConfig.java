package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * payment-service 전용 Kafka 토픽 선언.
 * ADR-30: 파티션 수 3 — create-topics.sh와 동일하게 유지.
 * ADR-11: 공통 jar 금지 — pg-service는 Phase 2에서 자체 KafkaTopicConfig를 갖는다.
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
        return TopicBuilder.name("payment.commands.confirm")
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    public NewTopic paymentCommandsConfirmDlq() {
        return TopicBuilder.name("payment.commands.confirm.dlq")
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    public NewTopic paymentEventsConfirmed() {
        return TopicBuilder.name("payment.events.confirmed")
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    public NewTopic paymentEventsStockCommitted() {
        return TopicBuilder.name("payment.events.stock-committed")
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }
}
