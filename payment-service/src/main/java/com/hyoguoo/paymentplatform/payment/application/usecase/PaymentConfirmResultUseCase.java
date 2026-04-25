package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.event.StockOutboxReadyEvent;
import com.hyoguoo.paymentplatform.payment.application.util.StockEventUuidDeriver;
import com.hyoguoo.paymentplatform.payment.application.port.out.EventDedupeStore;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentConfirmDlqPublisher;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockOutboxRepository;
import com.hyoguoo.paymentplatform.payment.application.service.FailureCompensationService;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.StockOutbox;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.PaymentTopics;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer.dto.ConfirmedEventMessage;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.event.StockCommittedEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * payment.events.confirmed 소비 후 결제 상태 분기 use-case.
 * ADR-04(2단 멱등성): eventUUID dedupe 선행, 처리 주체 결정.
 * ADR-14: stock 이벤트 ApplicationEvent 발행(commit/restore) 담당.
 *
 * <p>T-J1: AFTER_COMMIT stock Kafka 발행 회귀 완전 해소.
 * TX 내부에서 stock_outbox INSERT → StockOutboxReadyEvent 발행.
 * AFTER_COMMIT 리스너(StockOutboxImmediateEventHandler)가 @Async("outboxRelayExecutor")로
 * relay를 수행한다. outboxRelayExecutor의 T-I2 이중 래핑이 OTel Context + MDC를
 * submit 시점에 캡처하여 VT에서 정확히 복원 → traceparent 회귀 없음.
 * T-D2~T-I10의 ContextSnapshot/OTel/Observation 활성화 시도 경로 철거.
 *
 * <p>T-C3 two-phase lease 패턴:
 * <ol>
 *   <li>진입 시 {@code markWithLease(eventUuid, leaseTtl)} — shortTtl(기본 5분) 잠금</li>
 *   <li>processMessage 성공 후 {@code extendLease(eventUuid, longTtl)} — P8D로 연장</li>
 *   <li>실패 시 {@code remove(eventUuid)} — 재컨슘 허용. remove false이면 DLQ 전송</li>
 * </ol>
 *
 * <p>상태 분기:
 * <ul>
 *   <li>APPROVED → PaymentEvent DONE 전이 + stock_outbox INSERT(commit) + StockOutboxReadyEvent</li>
 *   <li>FAILED → PaymentEvent FAILED 전이 + FailureCompensationService.compensate(outbox INSERT + event)</li>
 *   <li>QUARANTINED → QuarantineCompensationHandler.handle(FCG 진입점) 위임</li>
 * </ul>
 */
@Slf4j
@Service
public class PaymentConfirmResultUseCase {

    /** T-C3: processMessage 성공 전 초기 lease TTL. 기본 5분. */
    static final Duration DEFAULT_LEASE_TTL = Duration.ofMinutes(5);
    /** T-C3: processMessage 성공 후 연장 TTL. Kafka retention(7d) + 버퍼(1d) = 8d. */
    static final Duration DEFAULT_LONG_TTL = Duration.ofDays(8);

    private final PaymentEventRepository paymentEventRepository;
    private final EventDedupeStore eventDedupeStore;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final QuarantineCompensationHandler quarantineCompensationHandler;
    private final LocalDateTimeProvider localDateTimeProvider;
    private final FailureCompensationService failureCompensationService;
    private final PaymentConfirmDlqPublisher paymentConfirmDlqPublisher;
    private final StockOutboxRepository stockOutboxRepository;
    private final ObjectMapper objectMapper;
    /** T-C3: processMessage 성공 전 초기 lease TTL. 기본 5분. K6: 생성자 파라미터 @Value로 이전. */
    private final Duration leaseTtl;
    /** T-C3: processMessage 성공 후 연장 TTL. K6: 생성자 파라미터 @Value로 이전. */
    private final Duration longTtl;

    public PaymentConfirmResultUseCase(
            PaymentEventRepository paymentEventRepository,
            EventDedupeStore eventDedupeStore,
            ApplicationEventPublisher applicationEventPublisher,
            QuarantineCompensationHandler quarantineCompensationHandler,
            LocalDateTimeProvider localDateTimeProvider,
            FailureCompensationService failureCompensationService,
            PaymentConfirmDlqPublisher paymentConfirmDlqPublisher,
            StockOutboxRepository stockOutboxRepository,
            ObjectMapper objectMapper,
            @Value("${payment.event-dedupe.lease-ttl:PT5M}") Duration leaseTtl,
            @Value("${payment.event-dedupe.ttl:P8D}") Duration longTtl) {
        this.paymentEventRepository = paymentEventRepository;
        this.eventDedupeStore = eventDedupeStore;
        this.applicationEventPublisher = applicationEventPublisher;
        this.quarantineCompensationHandler = quarantineCompensationHandler;
        this.localDateTimeProvider = localDateTimeProvider;
        this.failureCompensationService = failureCompensationService;
        this.paymentConfirmDlqPublisher = paymentConfirmDlqPublisher;
        this.stockOutboxRepository = stockOutboxRepository;
        this.objectMapper = objectMapper;
        this.leaseTtl = leaseTtl;
        this.longTtl = longTtl;
    }

    /**
     * T-C3 two-phase lease:
     * 1) markWithLease(shortTtl) — 처리 권한 획득. false이면 다른 consumer가 처리 중 → skip.
     * 2) processMessage 성공 → extendLease(longTtl).
     * 3) processMessage 실패 → remove. remove false이면 DLQ 전송 후 예외 재전파.
     */
    @Transactional(timeout = 5)
    public void handle(ConfirmedEventMessage message) {
        // 1단: eventUUID lease dedupe
        if (!eventDedupeStore.markWithLease(message.eventUuid(), leaseTtl)) {
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_DEDUPE,
                    () -> "orderId=" + message.orderId() + " eventUuid=" + message.eventUuid());
            return;
        }

        // TX 경계 불일치 방어: processMessage 실패 시 dedupe 기록 제거 → 재컨슘 허용
        // remove 실패(Redis flap) 시 dedupe 영구 잠금 → DLQ 전송으로 복구 경로 보장
        processMessageWithLeaseGuard(message);
    }

    /**
     * processMessage 호출 후 성공/실패 분기:
     * - 성공: extendLease로 TTL 연장
     * - 실패: remove 시도. remove false이면 DLQ 발행 후 예외 재전파
     *
     * <p>try 블록 내 외부 변수 재할당 금지 규약 준수 — private 메서드로 추출.
     */
    private void processMessageWithLeaseGuard(ConfirmedEventMessage message) {
        try {
            processMessage(message);
            eventDedupeStore.extendLease(message.eventUuid(), longTtl);
        } catch (RuntimeException e) {
            handleRemoveOnFailure(message.eventUuid(), e);
            throw e;
        }
    }

    /**
     * processMessage 실패 후 dedupe 기록 제거 시도.
     * remove 실패(false) 시 DLQ 발행으로 dedupe 영구 잠금 방지.
     */
    private void handleRemoveOnFailure(String eventUuid, RuntimeException originalException) {
        boolean removed = eventDedupeStore.remove(eventUuid);
        if (!removed) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_DEDUPE,
                    () -> "eventUuid=" + eventUuid + " remove=false"
                            + " cause=" + originalException.getMessage()
                            + " action=DLQ_PUBLISH");
            paymentConfirmDlqPublisher.publishDlq(eventUuid, originalException.getMessage());
        }
    }

    private void processMessage(ConfirmedEventMessage message) {
        // 2단: orderId로 PaymentEvent 조회
        PaymentEvent paymentEvent = paymentEventRepository
                .findByOrderId(message.orderId())
                .orElseThrow(() -> PaymentFoundException.of(PaymentErrorCode.PAYMENT_EVENT_NOT_FOUND));

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_START,
                () -> "orderId=" + message.orderId() + " status=" + message.status()
                        + " eventUuid=" + message.eventUuid());

        // 3단: status별 분기
        switch (message.status()) {
            case "APPROVED" -> handleApproved(paymentEvent, message);
            case "FAILED" -> handleFailed(paymentEvent, message.reasonCode());
            case "QUARANTINED" -> handleQuarantined(paymentEvent, message.reasonCode());
            default -> LogFmt.warn(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_UNKNOWN_STATUS,
                    () -> "orderId=" + message.orderId() + " status=" + message.status());
        }
    }

    /**
     * APPROVED 결과 처리.
     *
     * <p>ADR-15 역방향 방어선:
     * <ol>
     *   <li>수신 approvedAt null 방어 — null이면 {@link IllegalArgumentException}</li>
     *   <li>수신 amount vs paymentEvent 총액 대조 — 불일치 시 AMOUNT_MISMATCH QUARANTINED 전이</li>
     *   <li>일치 시 수신 approvedAt(OffsetDateTime→LocalDateTime 변환)을 done()에 주입</li>
     * </ol>
     *
     * <p>T-J1: stock commit outbox 패턴.
     * 기존 StockCommitRequestedEvent (T-D2~T-I10 경로) 철거.
     * TX 내부에서 각 PaymentOrder에 대해 stock_outbox INSERT + StockOutboxReadyEvent 발행.
     * AFTER_COMMIT 리스너(StockOutboxImmediateEventHandler)가 @Async("outboxRelayExecutor")로
     * relay를 트리거한다. outboxRelayExecutor의 이중 래핑이 traceparent 정확히 전파.
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

        paymentEvent.done(receivedApprovedAt, localDateTimeProvider.now());
        paymentEventRepository.saveOrUpdate(paymentEvent);

        // T-J1: TX 내부 stock_outbox INSERT + StockOutboxReadyEvent 발행
        // 기존 StockCommitRequestedEvent(T-D2~T-I10 경로) 철거.
        // outboxRelayExecutor(@Async, T-I2 이중 래핑)가 submit 시점 OTel Context + MDC를
        // VT에서 정확히 복원 → KafkaTemplate.observation-enabled=true 가 traceparent 자동 주입.
        LocalDateTime now = localDateTimeProvider.now();
        for (PaymentOrder order : paymentEvent.getPaymentOrderList()) {
            StockOutbox outbox = buildStockCommitOutbox(paymentEvent, order, now);
            StockOutbox saved = stockOutboxRepository.save(outbox);
            applicationEventPublisher.publishEvent(new StockOutboxReadyEvent(saved.getId()));
        }

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_DONE,
                () -> "orderId=" + paymentEvent.getOrderId());
    }

    /**
     * stock commit outbox row 빌드.
     * payload: StockCommittedEvent JSON 직렬화.
     * key: productId.toString() — 동일 상품 이벤트를 동일 파티션에 라우팅(ADR-12).
     *
     * <p>K1 fix: idempotencyKey는 (orderId, productId) 기반 결정론적 UUID v3으로 도출.
     * 기존 paymentEvent.getOrderId() 단일값 사용은 multi-product 결제 시 모든 이벤트가
     * 동일 dedupe key를 공유하여 product-service가 첫 product만 처리하는 회귀를 유발.
     * ADR-16 참고: StockEventUuidDeriver.derive(orderId, productId, "stock-commit").
     *
     * <p>K3 fix: StockCommittedEvent에 orderId(String) + expiresAt(Instant) 명시 전달.
     * producer가 직접 채워 consumer 측 fallback null 의존 제거.
     * expiresAt = Instant.now() + longTtl(8d) — consumer의 DEDUPE_TTL(8d)과 동일 정책.
     */
    private StockOutbox buildStockCommitOutbox(PaymentEvent paymentEvent, PaymentOrder order, LocalDateTime now) {
        String idempotencyKey = StockEventUuidDeriver.derive(
                paymentEvent.getOrderId(), order.getProductId(), "stock-commit");
        // K5: Instant.now() 직접 호출 제거 → localDateTimeProvider.nowInstant() 사용
        Instant occurredAt = localDateTimeProvider.nowInstant();
        Instant expiresAt = occurredAt.plus(longTtl);
        StockCommittedEvent event = new StockCommittedEvent(
                order.getProductId(),
                order.getQuantity(),
                idempotencyKey,
                occurredAt,
                paymentEvent.getOrderId(),
                expiresAt
        );
        String payloadJson = serializeToJson(event);
        return StockOutbox.create(
                PaymentTopics.EVENTS_STOCK_COMMITTED,
                String.valueOf(order.getProductId()),
                payloadJson,
                now
        );
    }

    /**
     * 수신 approvedAt 문자열을 LocalDateTime으로 파싱.
     *
     * @param approvedAtRaw ISO-8601 OffsetDateTime 문자열 (non-null 강제)
     * @return LocalDateTime (UTC 기준 — OffsetDateTime.toLocalDateTime)
     * @throws IllegalArgumentException approvedAt이 null인 경우
     */
    private static LocalDateTime parseApprovedAt(String approvedAtRaw) {
        if (approvedAtRaw == null) {
            throw new IllegalArgumentException(
                    "APPROVED 메시지에 approvedAt이 null입니다. ADR-15 방어선 위반.");
        }
        return OffsetDateTime.parse(approvedAtRaw).toLocalDateTime();
    }

    /**
     * 수신 amount와 paymentEvent 총액을 대조.
     *
     * <p>paymentEvent.getTotalAmount()은 BigDecimal(scale=0 원화) — longValue()로 안전 변환.
     * scale>0 케이스는 도메인 생성 시점에 이미 방어됨.
     *
     * @param paymentEvent 결제 이벤트
     * @param receivedAmount 수신 amount (Long, nullable — null이면 대조 불가 → 불일치로 간주)
     * @return true이면 불일치
     */
    private static boolean isAmountMismatch(PaymentEvent paymentEvent, Long receivedAmount) {
        if (receivedAmount == null) {
            return true;
        }
        long domainAmount = paymentEvent.getTotalAmount().longValueExact();
        return domainAmount != receivedAmount;
    }

    /**
     * FAILED 결과 처리.
     *
     * <p>ADR-13/T3-04b: 재고 복원은 FailureCompensationService.compensate(orderId, productId, qty) 경유.
     * 각 PaymentOrder의 실 수량(qty)을 전달해 product-service에서 정확한 재고가 복원된다.
     * T-B2: qty=0 플레이스홀더 경로(publish(orderId, productIds)) 오버로드 철거 완료.
     * T-J1: FailureCompensationService 내부에서 stock_outbox INSERT + StockOutboxReadyEvent 발행 —
     *        실제 Kafka 발행은 AFTER_COMMIT 리스너 담당(ADR-04).
     */
    private void handleFailed(PaymentEvent paymentEvent, String reasonCode) {
        paymentEvent.fail(reasonCode, localDateTimeProvider.now());
        paymentEventRepository.saveOrUpdate(paymentEvent);

        // stock.events.restore outbox INSERT: 각 주문 상품별 실 qty 포함 보상 이벤트 발행
        // T-B1: FailureCompensationService 경유 — 결정론적 UUID(ADR-16) + 실 qty 전달
        // T-J1: 내부에서 stock_outbox INSERT + StockOutboxReadyEvent 발행
        for (PaymentOrder order : paymentEvent.getPaymentOrderList()) {
            failureCompensationService.compensate(
                    paymentEvent.getOrderId(),
                    order.getProductId(),
                    order.getQuantity()
            );
        }

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_FAILED,
                () -> "orderId=" + paymentEvent.getOrderId() + " reasonCode=" + reasonCode);
    }

    private void handleQuarantined(PaymentEvent paymentEvent, String reasonCode) {
        // QUARANTINED 상태 전이는 handler 내부 책임 — consumer는 위임만
        quarantineCompensationHandler.handle(
                paymentEvent.getOrderId(),
                reasonCode
        );

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_QUARANTINED,
                () -> "orderId=" + paymentEvent.getOrderId() + " reasonCode=" + reasonCode);
    }

    /**
     * 도메인 이벤트를 JSON String으로 직렬화한다.
     * try 블록 내 외부 변수 재할당 금지 규약 준수 — private 메서드로 추출.
     */
    private String serializeToJson(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stock outbox payload 직렬화 실패: " + e.getMessage(), e);
        }
    }
}
