package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * payment.events.confirmed 소비 후 결제 상태 분기 use-case (PET-8 EOS 재작성).
 *
 * <p>EOS 전환 후 변경 사항:
 * <ul>
 *   <li>D7 진입 가드 — {@code isCompensatableByFailureHandler()} 로 종결 상태 진입 차단 (noop return)</li>
 *   <li>D5 멱등 마킹 — {@code PaymentEventDedupeStore.markIfAbsent()} 로 INSERT IGNORE, 0 row 시 비즈니스 skip</li>
 *   <li>D8 발행 측 결정성 — multi-product for-loop 안에서 {@code StockEventUuidDeriver.derive} 로 idempotencyKey 도출
 *       후 {@code stockCommittedKafkaTemplate.send} 직접 발행 (EOS producer tx 안에 buffer)</li>
 *   <li>위키 line 141 보장 — 0 row(중복) 시에도 발행은 항상 진행 (product-service dedupe 가 막음)</li>
 * </ul>
 *
 * <p>제거된 의존: {@code ApplicationEventPublisher} (StockOutboxReadyEvent 발행 용도) /
 * {@code StockOutboxRepository} / {@code StockOutboxFactory} — PET-9 에서 관련 묶음 전체 삭제 예정.
 *
 * <p>상태 분기:
 * <ul>
 *   <li>APPROVED → 금액 검증 → markPaymentAsDone + multi-product send loop</li>
 *   <li>FAILED → compensateAtomic (보상 먼저) → markPaymentAsFail (RDB 나중). RuntimeException 은 그대로 throw.</li>
 *   <li>QUARANTINED → compensateAtomic (보상 먼저) → quarantineCompensationHandler. 기존 순서 유지.</li>
 * </ul>
 *
 * <p>호출 순서 근거 (FAILED): DECISION §6 crash 표 — 구 순서(RDB → 보상)는 RDB commit 직후/보상 전 crash 시
 * isCompensatableByFailureHandler=false 로 재배달 noop → 보상 누락 silent loss. 새 순서(보상 → RDB)는 crash 후 재배달 시
 * compensateAtomic 의 dedup token 이 ALREADY_DONE 을 반환 → markPaymentAsFail 재진행 → 정합 보장.
 */
@Slf4j
@Service
public class PaymentConfirmResultUseCase {

    /** stock-committed expiresAt 계산용 TTL. Kafka retention(7 일) + 복구 버퍼(1 일) = 8 일. */
    private static final Duration STOCK_COMMITTED_TTL = Duration.ofDays(8);

    private final PaymentEventRepository paymentEventRepository;
    private final QuarantineCompensationHandler quarantineCompensationHandler;
    private final LocalDateTimeProvider localDateTimeProvider;
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
            LocalDateTimeProvider localDateTimeProvider,
            StockCachePort stockCachePort,
            PaymentEventDedupeStore paymentEventDedupeStore,
            @Qualifier("stockCommittedKafkaTemplate") KafkaTemplate<String, String> stockCommittedKafkaTemplate,
            PaymentCommandUseCase paymentCommandUseCase) {
        this.paymentEventRepository = paymentEventRepository;
        this.quarantineCompensationHandler = quarantineCompensationHandler;
        this.localDateTimeProvider = localDateTimeProvider;
        this.stockCachePort = stockCachePort;
        this.paymentEventDedupeStore = paymentEventDedupeStore;
        this.stockCommittedKafkaTemplate = stockCommittedKafkaTemplate;
        this.objectMapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        this.paymentCommandUseCase = paymentCommandUseCase;
    }

    /**
     * 메시지 처리 진입점.
     * D7 진입 가드 → D5 멱등 마킹 → 상태 분기.
     * RuntimeException 은 Spring Kafka DefaultErrorHandler(SCR-8) 가 처리한다.
     */
    @Transactional(timeout = 5)
    public void handle(ConfirmedEventMessage message) {
        PaymentEvent paymentEvent = paymentEventRepository
                .findByOrderId(message.orderId())
                .orElseThrow(() -> PaymentFoundException.of(PaymentErrorCode.PAYMENT_EVENT_NOT_FOUND));

        // D7 진입 가드 — 종결 상태(DONE/FAILED/CANCELED/PARTIAL_CANCELED/EXPIRED/QUARANTINED) 이면 noop
        if (!paymentEvent.getStatus().isCompensatableByFailureHandler()) {
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_UNKNOWN_STATUS,
                    () -> "D7 가드 skip — orderId=" + message.orderId()
                            + " status=" + paymentEvent.getStatus()
                            + " eventUuid=" + message.eventUuid());
            return;
        }

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_START,
                () -> "orderId=" + message.orderId() + " status=" + message.status()
                        + " eventUuid=" + message.eventUuid());

        // D5 멱등 마킹 — INSERT IGNORE, 0 row 면 비즈니스 skip (발행은 항상 진행)
        Instant expiresAt = localDateTimeProvider.nowInstant().plus(STOCK_COMMITTED_TTL);
        int affected = paymentEventDedupeStore.markIfAbsent(
                message.eventUuid(),
                paymentEvent.getId(),
                message.status(),
                expiresAt
        );

        if (affected == 0) {
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_START,
                    () -> "D5 중복 skip — orderId=" + message.orderId()
                            + " eventUuid=" + message.eventUuid()
                            + " action=SKIP_BUSINESS_ALWAYS_SEND");
            // 0 row 시 비즈니스 skip + 발행은 항상 진행 (위키 line 141)
            if ("APPROVED".equals(message.status())) {
                sendStockCommittedEvents(paymentEvent);
            }
            return;
        }

        switch (message.status()) {
            case "APPROVED" -> handleApproved(paymentEvent, message);
            case "FAILED" -> handleFailed(paymentEvent, message.reasonCode());
            case "QUARANTINED" -> handleQuarantined(paymentEvent, message.reasonCode());
            default -> LogFmt.warn(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_UNKNOWN_STATUS,
                    () -> "orderId=" + message.orderId() + " status=" + message.status());
        }
    }

    /**
     * APPROVED 결과 처리. 벤더가 한 번 승인한 후 우리 시스템이 다시 한 번 검증한다 — amount 와 approvedAt.
     *
     * <ol>
     *   <li>approvedAt null 방어 — null 이면 contract 위반이므로 즉시 예외</li>
     *   <li>amount 가 paymentEvent 총액과 다르면 AMOUNT_MISMATCH 로 격리(DONE 전이 안 함)</li>
     *   <li>둘 다 통과하면 수신 approvedAt 으로 PaymentCommandUseCase.markPaymentAsDone 위임</li>
     *   <li>multi-product for-loop 안에서 stock-committed 직접 발행 (EOS producer tx 버퍼)</li>
     * </ol>
     *
     * <p>D8: idempotencyKey = StockEventUuidDeriver.derive(orderId, productId, "stock-commit")
     * D4 결정: send 는 KafkaTransactionManager 가 관리하는 EOS 트랜잭션 안에 buffer.
     */
    private void handleApproved(PaymentEvent paymentEvent, ConfirmedEventMessage message) {
        LocalDateTime receivedApprovedAt = parseApprovedAt(message.approvedAt());

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

        // 외부 빈 경유 필수 — self-invocation 으로 호출하면 PaymentCommandUseCase 의 상태 전이 AOP 가 적용되지 않는다.
        paymentCommandUseCase.markPaymentAsDone(paymentEvent, receivedApprovedAt);

        sendStockCommittedEvents(paymentEvent);

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_DONE,
                () -> "orderId=" + paymentEvent.getOrderId());
    }

    /**
     * multi-product stock-committed 발행 for-loop.
     * EOS KafkaTemplate send 는 KafkaTransactionManager 가 관리하는 tx 안에 buffer.
     * 메시지 키 = productId (파티션 키 전략 — 동일 상품 이벤트 순서 보장).
     * 중복(0 row) 시에도 호출됨 — product-service stock_commit_dedupe 가 막음 (위키 line 141).
     */
    private void sendStockCommittedEvents(PaymentEvent paymentEvent) {
        Instant occurredAt = localDateTimeProvider.nowInstant();
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
        return serializeQuietly(payload);
    }

    private String serializeQuietly(StockCommittedEvent payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("StockCommittedEvent 직렬화 실패 — productId=" + payload.productId(), e);
        }
    }

    /**
     * 수신 approvedAt 문자열을 LocalDateTime 으로 변환.
     * approvedAt 은 ISO_OFFSET_DATE_TIME contract(non-null) 위반 시 즉시 예외.
     */
    private static LocalDateTime parseApprovedAt(String approvedAtRaw) {
        if (approvedAtRaw == null) {
            throw new IllegalArgumentException("APPROVED 메시지에 approvedAt 이 null 입니다.");
        }
        return OffsetDateTime.parse(approvedAtRaw).toLocalDateTime();
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
     * FAILED 결과 처리 — 호출 순서: 보상(compensateAtomic) 먼저, RDB(markPaymentAsFail) 나중.
     *
     * <p>호출 순서 근거: 구 순서(RDB → 보상)는 RDB commit 직후/보상 전 crash 시
     * isCompensatableByFailureHandler=false 로 재배달이 noop 처리돼 보상 누락 silent loss 발생.
     * 새 순서(보상 → RDB)에서 crash 후 재배달 시 compensateAtomic 의 dedup token 이
     * ALREADY_DONE 을 반환하므로 markPaymentAsFail 을 재진행해 정합이 보장된다.
     *
     * <p>D7 진입 가드 통합 후 이 메서드 내부의 isTerminal 가드는 제거됨.
     * 종결 상태 방어는 handle() 진입점 D7 가드가 담당한다.
     *
     * <p>RuntimeException 은 그대로 throw — Spring Kafka DefaultErrorHandler(SCR-8) 가 retry / DLQ 책임.
     */
    private void handleFailed(PaymentEvent paymentEvent, String reasonCode) {
        // 보상 먼저 (ALREADY_DONE 이어도 RDB 진행)
        stockCachePort.compensateAtomic(paymentEvent.getOrderId(), paymentEvent.getPaymentOrderList());

        // RDB 나중
        paymentCommandUseCase.markPaymentAsFail(paymentEvent, reasonCode);

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_FAILED,
                () -> "orderId=" + paymentEvent.getOrderId() + " reasonCode=" + reasonCode);
    }

    /**
     * QUARANTINED 결과 처리 — 기존 순서 유지 (보상 → quarantineHandler).
     * compensateStockCache for-loop 을 compensateAtomic 직접 호출로 교체.
     *
     * <p>implementer 주의: 이 메서드는 "순서를 뒤집는" 게 아니라 "메서드만 교체"한다.
     * 기존 보상 → quarantineHandler 순서 외로 변경하면 PITFALLS #11 보상 트랜잭션 중복 진입 race 신설 위험.
     *
     * <p>D7 진입 가드 통합 후 이 메서드 내부의 isTerminal 가드는 제거됨.
     *
     * <p>RuntimeException 은 그대로 throw — Spring Kafka DefaultErrorHandler(SCR-8) 가 retry / DLQ 책임.
     */
    private void handleQuarantined(PaymentEvent paymentEvent, String reasonCode) {
        // 보상 먼저 (기존 순서 유지 — 메서드 교체만)
        stockCachePort.compensateAtomic(paymentEvent.getOrderId(), paymentEvent.getPaymentOrderList());

        quarantineCompensationHandler.handle(
                paymentEvent.getOrderId(),
                reasonCode
        );

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_QUARANTINED,
                () -> "orderId=" + paymentEvent.getOrderId() + " reasonCode=" + reasonCode);
    }
}
