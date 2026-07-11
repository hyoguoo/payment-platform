package com.hyoguoo.paymentplatform.payment.infrastructure.config;

import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * payment-service 전용 Kafka 토픽 선언.
 * 파티션 수 3 — create-topics.sh 의 운영 토폴로지와 동일하게 유지한다.
 * pg-service 는 자체 KafkaTopicConfig 를 가지며 공통 jar 를 공유하지 않는다.
 *
 * <p>로컬/프로덕션 Compose 환경에서는 auto.create.topics.enable=false 이므로
 * create-topics.sh 가 실제 토픽 생성을 담당한다.
 * 이 설정은 선언적 문서 + 테스트/임베디드 환경용이다.
 */
@Configuration
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class KafkaTopicConfig {

    private static final int PARTITIONS = 3;
    private static final int REPLICAS = 1;

    /**
     * {@code events.confirmed.dlq} 전용 retention — 10일.
     * DLQ 재주입 나이 게이트(DONE 종결 시각 + P8D = 8일, {@code DlqReprocessUseCase}
     * {@code STOCK_COMMIT_DEDUPE_TTL})보다 반드시 커야 한다. retention 이 이 값보다 짧으면
     * 나이 게이트가 아직 열려 있는(재주입 허용 구간) 메시지가 브로커에서 먼저 삭제되어
     * "게이트는 통과했지만 원본 메시지가 없어 재주입 불가"인 사각이 생긴다. 8일(P8D) 초과 +
     * 과도하지 않은 운영 버퍼로 10일을 채택.
     */
    private static final String EVENTS_CONFIRMED_DLQ_RETENTION_MS = "864000000";

    @Bean
    public NewTopic paymentCommandsConfirm() {
        return TopicBuilder.name(PaymentTopics.COMMANDS_CONFIRM)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    public NewTopic paymentCommandsConfirmDlq() {
        return TopicBuilder.name(PaymentTopics.COMMANDS_CONFIRM_DLQ)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    public NewTopic paymentEventsConfirmed() {
        return TopicBuilder.name(PaymentTopics.EVENTS_CONFIRMED)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    @Bean
    public NewTopic paymentEventsConfirmedDlq() {
        return TopicBuilder.name(PaymentTopics.EVENTS_CONFIRMED_DLQ)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .config(TopicConfig.RETENTION_MS_CONFIG, EVENTS_CONFIRMED_DLQ_RETENTION_MS)
                .build();
    }

    @Bean
    public NewTopic paymentEventsStockCommitted() {
        return TopicBuilder.name(PaymentTopics.EVENTS_STOCK_COMMITTED)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }
}
