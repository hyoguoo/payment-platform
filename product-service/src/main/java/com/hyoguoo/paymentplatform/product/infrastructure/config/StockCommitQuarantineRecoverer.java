package com.hyoguoo.paymentplatform.product.infrastructure.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.product.core.common.log.EventType;
import com.hyoguoo.paymentplatform.product.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.product.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.product.infrastructure.messaging.ProductTopics;
import java.util.Optional;
import java.util.function.BiFunction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.lang.Nullable;

/**
 * {@link ProductTopics#PAYMENT_EVENTS_STOCK_COMMITTED} 소비 실패를 격리 토픽으로 보내는 recoverer.
 *
 * <p>원본 토픽의 메시지 키는 productId 단독이다 — payment-service 가 같은 상품 이벤트의 순서를
 * 보장하려고 productId 로만 파티셔닝해 발행한다. 그런데 재고 확정 음수 가드가 던지는 예외는
 * dedupe 기록 삽입과 같은 트랜잭션 안에서 함께 롤백되어, 재전송마다 새 이벤트로 처리된다. 결제
 * 쪽 재발행까지 겹치면 같은 사고가 여러 orderId 로 격리 토픽에 쌓일 수 있는데, 키가 productId
 * 단독이면 이 사고들을 주문 단위로 묶어 볼 방법이 없다 — 그래서 격리 메시지의 키를
 * {@code orderId:productId} 조합으로 다시 만든다.
 *
 * <p>원본 페이로드(JSON String) 파싱에 실패하면(역직렬화 자체가 실패 원인인 경우 등) 원본 키로
 * 되돌아간다 — 키 조합 실패가 격리 자체를 막지 않는다.
 */
public class StockCommitQuarantineRecoverer extends DeadLetterPublishingRecoverer {

    private static final Logger log = LoggerFactory.getLogger(StockCommitQuarantineRecoverer.class);

    private final ObjectMapper objectMapper;

    public StockCommitQuarantineRecoverer(
            KafkaOperations<? extends Object, ? extends Object> template,
            ObjectMapper objectMapper,
            BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver) {
        super(template, destinationResolver);
        this.objectMapper = objectMapper;
    }

    /**
     * 격리 토픽은 String key.serializer 를 쓴다 — {@code key}/{@code value} 파라미터는 부모 클래스
     * 계약상 역직렬화 실패 시의 raw byte[] 페이로드 전용이라(정상 케이스는 항상 null) 그대로 부모에
     * 위임해 원본 키/값을 얻은 뒤, 조합 키가 있으면 키 필드만 String 으로 다시 감싼다. byte[] 를
     * 그대로 키로 흘리면 StringSerializer 가 {@link org.apache.kafka.common.errors.SerializationException}
     * 을 던진다.
     */
    @Override
    protected ProducerRecord<Object, Object> createProducerRecord(
            ConsumerRecord<?, ?> record, TopicPartition topicPartition, Headers headers,
            @Nullable byte[] key, @Nullable byte[] value) {

        ProducerRecord<Object, Object> original = super.createProducerRecord(record, topicPartition, headers, key, value);
        return resolveIncidentKey(record)
                .<ProducerRecord<Object, Object>>map(incidentKey -> new ProducerRecord<>(
                        original.topic(), original.partition(), incidentKey, original.value(), original.headers()))
                .orElse(original);
    }

    /**
     * 원본 페이로드에서 orderId·productId 를 읽어 {@code orderId:productId} 조합 키를 만든다.
     * 두 필드 중 하나라도 없으면(또는 파싱 자체가 실패하면) 빈 값을 돌려주고, 호출자가 원본 키로
     * fallback 한다.
     */
    private Optional<String> resolveIncidentKey(ConsumerRecord<?, ?> record) {
        if (!(record.value() instanceof String json)) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String orderId = node.path("orderId").asText(null);
            if (orderId == null || orderId.isBlank() || !node.hasNonNull("productId")) {
                LogFmt.warn(log, LogDomain.STOCK, EventType.STOCK_COMMIT_QUARANTINE_KEY_FALLBACK,
                        () -> "orderId 또는 productId 누락 — 원본 키로 격리: partition=" + record.partition()
                                + " offset=" + record.offset());
                return Optional.empty();
            }
            return Optional.of(orderId + ":" + node.path("productId").asLong());
        } catch (JsonProcessingException e) {
            LogFmt.warn(log, LogDomain.STOCK, EventType.STOCK_COMMIT_QUARANTINE_KEY_FALLBACK,
                    () -> "페이로드 파싱 실패 — 원본 키로 격리: partition=" + record.partition()
                            + " offset=" + record.offset());
            return Optional.empty();
        }
    }
}
