package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmStatus;
import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.application.dto.event.StockCommittedEvent;
import com.hyoguoo.paymentplatform.payment.application.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventDedupeStore;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.util.StockEventUuidDeriver;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * payment.events.confirmed 를 소비해 결제 상태를 분기하는 use-case.
 *
 * <ul>
 *   <li>APPROVED → 금액·승인시각 재검증 후 결제 완료 + 재고 확정 이벤트 발행</li>
 *   <li>FAILED → 재고 보상 후 결제 실패 마킹</li>
 *   <li>QUARANTINED → 재고 보상 후 격리 핸들러 위임</li>
 * </ul>
 *
 * <p>FAILED·QUARANTINED 는 보상을 먼저 하고 RDB 상태 전이를 나중에 한다. RDB 를 먼저 커밋하면
 * 보상 직전에 crash 했을 때 재배달이 종결 상태 가드에 막혀 보상이 조용히 유실되기 때문이다.
 *
 * <h2>트랜잭션 매니저 분리 원칙</h2>
 *
 * <p>{@link #handle} 의 {@code @Transactional(transactionManager = "transactionManager")} 는
 * {@link com.hyoguoo.paymentplatform.payment.core.config.JpaConfig#transactionManager} 가 등록하는
 * {@code @Primary} JPA 트랜잭션 매니저를 명시적으로 지정한다. 이로써 이 DB 트랜잭션은
 * 컨슈머 컨테이너가 관리하는 Kafka {@code KafkaTransactionManager}(qualifier: {@code kafkaTransactionManager})와
 * 완전히 분리된다 — Kafka tx 와 JPA tx 는 별개의 트랜잭션 범위로 운영된다.
 *
 * <h2>best-effort 1PC 한계</h2>
 *
 * <p>JPA DB 트랜잭션 commit 과 Kafka EOS 트랜잭션 commit 은 원자적으로 묶이지 않는다 (2PC 미지원).
 * DB commit 성공 후 Kafka commit 전 crash 가 발생하면 재배달이 일어난다.
 * 이 경우 {@code payment_event_dedupe} 의 {@code INSERT IGNORE} 멱등 마킹이
 * 중복 비즈니스 로직 실행을 흡수한다 — best-effort 1PC 보상 설계.
 */
@Slf4j
@Service
public class PaymentConfirmResultUseCase {

    /** stock-committed expiresAt 계산용 TTL. Kafka retention(7 일) + 복구 버퍼(1 일) = 8 일. */
    private static final Duration STOCK_COMMITTED_TTL = Duration.ofDays(8);

    private final PaymentEventRepository paymentEventRepository;
    private final QuarantineCompensationHandler quarantineCompensationHandler;
    private final Clock clock;
    private final StockCachePort stockCachePort;
    private final PaymentEventDedupeStore paymentEventDedupeStore;
    private final KafkaTemplate<String, String> stockCommittedKafkaTemplate;
    private final ObjectMapper objectMapper;
    /**
     * 상태 전이 위임 use-case. self-invocation 으로 호출하면
     * {@code @PublishDomainEvent} / {@code @PaymentStatusChange} AOP 가 적용되지 않으므로 외부 빈을 통해 호출해야 한다.
     */
    private final PaymentCommandUseCase paymentCommandUseCase;

    public PaymentConfirmResultUseCase(
            PaymentEventRepository paymentEventRepository,
            QuarantineCompensationHandler quarantineCompensationHandler,
            Clock clock,
            StockCachePort stockCachePort,
            PaymentEventDedupeStore paymentEventDedupeStore,
            @Qualifier("stockCommittedKafkaTemplate") KafkaTemplate<String, String> stockCommittedKafkaTemplate,
            PaymentCommandUseCase paymentCommandUseCase) {
        this.paymentEventRepository = paymentEventRepository;
        this.quarantineCompensationHandler = quarantineCompensationHandler;
        this.clock = clock;
        this.stockCachePort = stockCachePort;
        this.paymentEventDedupeStore = paymentEventDedupeStore;
        this.stockCommittedKafkaTemplate = stockCommittedKafkaTemplate;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        this.paymentCommandUseCase = paymentCommandUseCase;
    }

    /**
     * 메시지 처리 진입점. 종결 상태 가드 → 멱등 마킹 → 상태 분기 순으로 진행한다.
     * RuntimeException 은 그대로 던져 Kafka 에러 핸들러가 재시도/DLQ 를 결정한다.
     *
     * <p>qualifier {@code "transactionManager"} 는 {@code JpaConfig#transactionManager} 빈을 명시 지정한다.
     * Kafka {@code KafkaTransactionManager} 와의 혼동을 방지하고,
     * DB tx 가 Kafka tx 와 별개의 JPA 매니저로 동작함을 코드 수준에서 보장한다.
     */
    @Transactional(transactionManager = "transactionManager", timeout = 5)
    public void handle(ConfirmedEventMessage message) {
        PaymentEvent paymentEvent = paymentEventRepository
                .findByOrderId(message.orderId())
                .orElseThrow(() -> PaymentFoundException.of(PaymentErrorCode.PAYMENT_EVENT_NOT_FOUND));

        // 종결 상태면 이미 처리된 메시지이므로 무시한다.
        if (!paymentEvent.getStatus().canApplyConfirmResult()) {
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_UNKNOWN_STATUS,
                    () -> "종결 상태 skip — orderId=" + message.orderId()
                            + " status=" + paymentEvent.getStatus()
                            + " eventUuid=" + message.eventUuid());
            return;
        }

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_START,
                () -> "orderId=" + message.orderId() + " status=" + message.status()
                        + " eventUuid=" + message.eventUuid());

        // 멱등 마킹: 이미 처리된 event_uuid 면 affected=0.
        Instant expiresAt = clock.instant().plus(STOCK_COMMITTED_TTL);
        int affected = paymentEventDedupeStore.markIfAbsent(
                message.eventUuid(),
                paymentEvent.getId(),
                message.status(),
                expiresAt
        );

        ConfirmStatus confirmStatus = ConfirmStatus.from(message.status());

        if (affected == 0) {
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_START,
                    () -> "중복 skip — orderId=" + message.orderId()
                            + " eventUuid=" + message.eventUuid());
            // 중복이면 비즈니스 로직은 건너뛰되, 재고 확정 발행은 항상 수행한다 (product 측에서 다시 멱등 처리).
            if (confirmStatus == ConfirmStatus.APPROVED) {
                sendStockCommittedEvents(paymentEvent);
            }
            return;
        }

        switch (confirmStatus) {
            case APPROVED -> handleApproved(paymentEvent, message);
            case FAILED -> handleFailed(paymentEvent, message.reasonCode());
            case QUARANTINED -> handleQuarantined(paymentEvent, message.reasonCode());
            case UNKNOWN -> LogFmt.warn(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_UNKNOWN_STATUS,
                    () -> "orderId=" + message.orderId() + " status=" + message.status());
        }
    }

    /**
     * APPROVED 처리. 벤더 승인 후 우리 쪽에서 금액과 승인시각을 다시 검증한다.
     * 금액이 불일치하면 완료 전이 없이 격리하고, 통과하면 결제 완료 후 재고 확정 이벤트를 발행한다.
     */
    private void handleApproved(PaymentEvent paymentEvent, ConfirmedEventMessage message) {
        // D8 — parseApprovedAt 은 OffsetDateTime.parse().toInstant() 로 오프셋 보존 정규화 (T14 완료)
        Instant receivedApprovedAt = parseApprovedAt(message.approvedAt());

        if (isAmountMismatch(paymentEvent, message.amount())) {
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_DONE,
                    () -> "orderId=" + paymentEvent.getOrderId()
                            + " expected=" + paymentEvent.getTotalAmount().longValue()
                            + " received=" + message.amount()
                            + " action=QUARANTINE_AMOUNT_MISMATCH");
            quarantineCompensationHandler.handle(
                    paymentEvent.getOrderId(),
                    PaymentErrorCode.AMOUNT_MISMATCH.name()
            );
            return;
        }

        // 외부 빈 경유 필수 — self-invocation 으로 호출하면 상태 전이 AOP 가 적용되지 않는다.
        paymentCommandUseCase.markPaymentAsDone(paymentEvent, receivedApprovedAt);

        sendStockCommittedEvents(paymentEvent);

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_DONE,
                () -> "orderId=" + paymentEvent.getOrderId());
    }

    /**
     * 주문에 속한 상품마다 stock-committed 이벤트를 발행한다.
     * 메시지 키는 productId — 동일 상품 이벤트의 순서를 보장한다.
     * 발행은 EOS 프로듀서 트랜잭션에 묶이며, 중복 수신 시에도 발행한다 (product 측에서 멱등 처리).
     */
    private void sendStockCommittedEvents(PaymentEvent paymentEvent) {
        Instant occurredAt = clock.instant();
        Instant expiresAt = occurredAt.plus(STOCK_COMMITTED_TTL);

        for (PaymentOrder order : paymentEvent.getPaymentOrderList()) {
            String idempotencyKey = StockEventUuidDeriver.derive(
                    paymentEvent.getOrderId(), order.getProductId(), "stock-commit");
            StockCommittedEvent payload = new StockCommittedEvent(
                    order.getProductId(),
                    order.getQuantity(),
                    idempotencyKey,
                    occurredAt,
                    paymentEvent.getOrderId(),
                    expiresAt
            );
            String json = serializeToJson(payload);
            stockCommittedKafkaTemplate.send(
                    PaymentTopics.EVENTS_STOCK_COMMITTED,
                    String.valueOf(order.getProductId()),
                    json
            );
        }
    }

    private String serializeToJson(StockCommittedEvent payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("StockCommittedEvent 직렬화 실패 — productId=" + payload.productId(), e);
        }
    }

    /**
     * 수신 approvedAt 문자열을 Instant 로 변환.
     * approvedAt 은 ISO_OFFSET_DATE_TIME contract(non-null) 위반 시 즉시 예외.
     *
     * <p>D8 — {@code OffsetDateTime.parse().toInstant()} 로 오프셋을 보존하여 정산 앵커 UTC 절대시점을 정규화한다.
     * KST(+09:00) 등 비-UTC 오프셋 입력도 9시간 오차 없이 UTC 절대시점으로 변환된다(AC9).
     * {@code toLocalDateTime()}을 사용하면 오프셋이 무시되어 최대 9시간 오차가 발생하므로 금지한다.
     *
     * <p>package-private: {@code PaymentConfirmResultUseCaseApprovedAtTest} 에서 직접 단정.
     */
    static Instant parseApprovedAt(String approvedAtRaw) {
        if (approvedAtRaw == null) {
            throw new IllegalArgumentException("APPROVED 메시지에 approvedAt 이 null 입니다.");
        }
        return OffsetDateTime.parse(approvedAtRaw).toInstant();
    }

    /**
     * 수신 amount 와 paymentEvent 총액 대조. paymentEvent.getTotalAmount() 는 원화 정수(scale=0)만 들어오므로
     * longValueExact 가 안전하다. 수신 amount 가 null 이면 비교 불가 → 불일치로 간주해 격리한다.
     */
    private static boolean isAmountMismatch(PaymentEvent paymentEvent, Long receivedAmount) {
        if (receivedAmount == null) {
            return true;
        }
        long domainAmount = paymentEvent.getTotalAmount().longValueExact();
        return domainAmount != receivedAmount;
    }

    /**
     * FAILED 처리. 재고 보상을 먼저 하고 결제 실패 마킹을 나중에 한다(순서 이유는 클래스 주석 참고).
     */
    private void handleFailed(PaymentEvent paymentEvent, String reasonCode) {
        stockCachePort.compensateAtomic(paymentEvent.getOrderId(), paymentEvent.getPaymentOrderList());

        paymentCommandUseCase.markPaymentAsFail(paymentEvent, reasonCode);

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_FAILED,
                () -> "orderId=" + paymentEvent.getOrderId() + " reasonCode=" + reasonCode);
    }

    /**
     * QUARANTINED 처리. 재고 보상 후 격리 핸들러에 위임한다.
     * 보상 → 핸들러 순서는 유지해야 한다 — 뒤집으면 보상 트랜잭션 중복 진입 race 가 생긴다.
     */
    private void handleQuarantined(PaymentEvent paymentEvent, String reasonCode) {
        stockCachePort.compensateAtomic(paymentEvent.getOrderId(), paymentEvent.getPaymentOrderList());

        quarantineCompensationHandler.handle(
                paymentEvent.getOrderId(),
                reasonCode
        );

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_QUARANTINED,
                () -> "orderId=" + paymentEvent.getOrderId() + " reasonCode=" + reasonCode);
    }
}
