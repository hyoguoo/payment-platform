package com.hyoguoo.paymentplatform.payment.infrastructure.messaging.publisher;

import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.application.port.out.DlqReprocessPort;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link DlqReprocessPort} 유일 Kafka 구현체.
 *
 * <p>{@code events.confirmed.dlq} 를 on-demand 로 읽어(offset commit 없음) 대상 orderId 의
 * 가장 최근(레코드 timestamp 기준) 메시지를 원 토픽({@code payment.events.confirmed})으로
 * 재발행한다 — 기존 EOS 컨슈머({@code ConfirmedEventConsumer})가 그대로 재처리한다.
 *
 * <p><b>읽기 커서 전략</b>: 매 호출마다 새 {@link KafkaConsumer} 를 생성해 전 파티션을
 * {@code assign()} + {@code seekToBeginning()} 으로 처음부터 끝까지 스캔하고, 컨슈머 그룹
 * 오프셋을 커밋하지 않는다. 반복 노출(같은 DLQ 레코드를 다시 스캔)은 원 토픽 EOS 컨슈머의
 * 다층 멱등 방어(consumer dedupe / CAS / product 결정적 키) + 이 포트를 감싸는
 * {@code DlqReprocessUseCase} 의 재주입 이력이 흡수한다. 재주입 판단(언제·몇 번)은 관리자가
 * 매 호출 단위로 쥔다 — 자동 폴링/스케줄 재시도는 하지 않는다.
 *
 * <p>같은 orderId 로 DLQ 에 레코드가 여러 건 쌓여 있으면(반복 실패 등) 가장 최근 레코드
 * 하나만 재발행한다 — 오래된 레코드를 중복 재발행해 불필요한 재처리를 유발하지 않기 위함.
 *
 * <p>ConditionalOnProperty: spring.kafka.bootstrap-servers 가 설정된 환경에서만 빈으로 등록된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")
public class KafkaDlqReprocessAdapter implements DlqReprocessPort {

    private static final long POLL_INTERVAL_MILLIS = 500L;
    private static final String CONSUMER_GROUP_ID = "payment-dlq-reprocess";

    private final KafkaTemplate<String, String> confirmedKafkaTemplate;
    private final String bootstrapServers;
    private final long readTimeoutMillis;

    public KafkaDlqReprocessAdapter(
            @Qualifier("confirmedKafkaTemplate") KafkaTemplate<String, String> confirmedKafkaTemplate,
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${payment.kafka.dlq-reprocess.read-timeout-millis:10000}") long readTimeoutMillis
    ) {
        this.confirmedKafkaTemplate = confirmedKafkaTemplate;
        this.bootstrapServers = bootstrapServers;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public void reprocess(String orderId) {
        String payload = readLatestDlqPayload(orderId);
        confirmedKafkaTemplate.send(PaymentTopics.EVENTS_CONFIRMED, orderId, payload);
        // 결과 로그(PAYMENT_DLQ_REPROCESS_SUCCESS)는 호출자 DlqReprocessUseCase 가 담당한다 —
        // 이 어댑터는 저수준 Kafka 발행 사실만 남긴다(KafkaMessagePublisher 와 동일 이벤트타입 재사용).
        LogFmt.debug(log, LogDomain.PAYMENT, EventType.KAFKA_PUBLISH_SUCCESS,
                () -> "topic=" + PaymentTopics.EVENTS_CONFIRMED + " key=" + orderId
                        + " action=dlq_reprocess_republish");
    }

    /**
     * {@code events.confirmed.dlq} 를 처음부터 끝까지 스캔해 지정 orderId 의 가장 최근 페이로드를 반환한다.
     */
    private String readLatestDlqPayload(String orderId) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(buildConsumerProps())) {
            List<TopicPartition> partitions = assignAllPartitions(consumer, PaymentTopics.EVENTS_CONFIRMED_DLQ);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            return pollLatestMatchingPayload(consumer, endOffsets, orderId)
                    .orElseThrow(() -> new IllegalStateException(
                            "재주입 대상 DLQ 레코드 없음 topic=" + PaymentTopics.EVENTS_CONFIRMED_DLQ
                                    + " orderId=" + orderId));
        }
    }

    private List<TopicPartition> assignAllPartitions(KafkaConsumer<String, String> consumer, String topic) {
        List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic);
        if (partitionInfos == null || partitionInfos.isEmpty()) {
            throw new IllegalStateException("DLQ 토픽 파티션 정보 없음 topic=" + topic);
        }
        List<TopicPartition> partitions = partitionInfos.stream()
                .map(info -> new TopicPartition(info.topic(), info.partition()))
                .collect(Collectors.toList());
        consumer.assign(partitions);
        consumer.seekToBeginning(partitions);
        return partitions;
    }

    private Optional<String> pollLatestMatchingPayload(
            KafkaConsumer<String, String> consumer, Map<TopicPartition, Long> endOffsets, String orderId) {
        String latestPayload = null;
        long latestTimestamp = Long.MIN_VALUE;
        long deadline = System.currentTimeMillis() + readTimeoutMillis;

        while (!isFullyConsumed(consumer, endOffsets) && System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(POLL_INTERVAL_MILLIS));
            for (ConsumerRecord<String, String> record : records) {
                if (orderId.equals(record.key()) && record.timestamp() >= latestTimestamp) {
                    latestTimestamp = record.timestamp();
                    latestPayload = record.value();
                }
            }
        }

        return Optional.ofNullable(latestPayload);
    }

    private boolean isFullyConsumed(KafkaConsumer<String, String> consumer, Map<TopicPartition, Long> endOffsets) {
        return endOffsets.entrySet().stream()
                .allMatch(entry -> consumer.position(entry.getKey()) >= entry.getValue());
    }

    private Map<String, Object> buildConsumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, CONSUMER_GROUP_ID);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        // assign() 기반 수동 조회라 그룹 코디네이션에 참여하지 않는다 — 오프셋 커밋도 하지 않는다
        // (읽기 커서 전략: 반복 노출은 원 토픽 EOS 컨슈머의 멱등 체인 + 재주입 이력이 흡수).
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return props;
    }
}
