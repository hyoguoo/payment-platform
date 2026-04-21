package com.hyoguoo.paymentplatform.product.infrastructure.config;

import com.hyoguoo.paymentplatform.product.infrastructure.messaging.ProductTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * product-service 전용 Kafka 토픽 선언.
 * ADR-30: 파티션 수 3 — create-topics.sh와 동일하게 유지.
 * ADR-30(공통 jar 금지): 다른 서비스의 KafkaTopicConfig를 참조하지 않고 독립 선언.
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
    public NewTopic productEventsStockSnapshot() {
        return TopicBuilder.name(ProductTopics.EVENTS_STOCK_SNAPSHOT)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }
}
