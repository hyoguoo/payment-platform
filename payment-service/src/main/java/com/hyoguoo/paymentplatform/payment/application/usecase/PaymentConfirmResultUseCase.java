package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.event.StockOutboxReadyEvent;
import com.hyoguoo.paymentplatform.payment.application.util.StockOutboxFactory;
import com.hyoguoo.paymentplatform.payment.application.port.out.EventDedupeStore;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentConfirmDlqPublisher;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCachePort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockOutboxRepository;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.domain.StockOutbox;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.application.dto.event.ConfirmedEventMessage;
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
 *
 * <p>2 단 멱등성: eventUuid dedupe 가 선행하고 그 다음 orderId 로 PaymentEvent 를 조회한다.
 * stock 이벤트 발행은 ApplicationEvent 로 위임해 TX 경계와 직접 결합하지 않는다.
 *
 * <p>stock 이벤트 발행 경로: TX 내부에서 stock_outbox row 를 INSERT 하고
 * {@link StockOutboxReadyEvent} 를 발행한다. AFTER_COMMIT 리스너가 비동기로 relay 를 트리거하며,
 * relay executor 가 OTel Context 와 MDC 를 submit 시점에 캡처해 VT 에서 복원하므로
 * traceparent 가 끊기지 않는다. 직접 KafkaTemplate.send 를 호출하면 이 보장이 깨진다.
 *
 * <p>two-phase lease:
 * <ol>
 *   <li>진입 시 {@code markWithLease(eventUuid, leaseTtl)} — short TTL(기본 5 분) 로 잠금. false 이면 다른 consumer 가 이미 처리 중.</li>
 *   <li>processMessage 성공 후 {@code extendLease(eventUuid, longTtl)} — Kafka retention + 버퍼(8 일) 로 연장.</li>
 *   <li>실패 시 {@code remove(eventUuid)} — 재컨슘 허용. remove false 면 DLQ 발행으로 dedupe 영구 잠금 방지.</li>
 * </ol>
 *
 * <p>상태 분기:
 * <ul>
 *   <li>APPROVED → PaymentCommandUseCase.markPaymentAsDone 위임 + stock_outbox INSERT + StockOutboxReadyEvent</li>
 *   <li>FAILED → PaymentCommandUseCase.markPaymentAsFail 위임 + 각 PaymentOrder 별 stockCachePort.increment (Redis 보상)</li>
 *   <li>QUARANTINED → 각 PaymentOrder 별 stockCachePort.increment + QuarantineCompensationHandler.handle 위임</li>
 * </ul>
 *
 * <p>재고 모델: redis-stock = product RDB 의 선차감 캐시. PG 결과별로 payment 가 자기 책임으로 보상한다.
 * stock.events.restore 토픽 발행은 폐기 — product RDB 는 APPROVED 시 누적 차감만 받는다.
 */
@Slf4j
@Service
public class PaymentConfirmResultUseCase {

    /** processMessage 성공 전 초기 lease TTL. 기본 5 분 — short TTL 로 처리 권한만 잠근다. */
    static final Duration DEFAULT_LEASE_TTL = Duration.ofMinutes(5);
    /** processMessage 성공 후 연장 TTL. Kafka retention(7 일) + 복구 버퍼(1 일) = 8 일. */
    static final Duration DEFAULT_LONG_TTL = Duration.ofDays(8);

    private final PaymentEventRepository paymentEventRepository;
    private final EventDedupeStore eventDedupeStore;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final QuarantineCompensationHandler quarantineCompensationHandler;
    private final LocalDateTimeProvider localDateTimeProvider;
    private final StockCachePort stockCachePort;
    private final PaymentConfirmDlqPublisher paymentConfirmDlqPublisher;
    private final StockOutboxRepository stockOutboxRepository;
    private final ObjectMapper objectMapper;
    private final Duration leaseTtl;
    private final Duration longTtl;
    /**
     * 상태 전이 위임 use-case. self-invocation 으로 호출하면
     * {@code @PublishDomainEvent} / {@code @PaymentStatusChange} AOP 가 적용되지 않으므로 외부 빈을 통해 호출해야 한다.
     */
    private final PaymentCommandUseCase paymentCommandUseCase;

    public PaymentConfirmResultUseCase(
            PaymentEventRepository paymentEventRepository,
            EventDedupeStore eventDedupeStore,
            ApplicationEventPublisher applicationEventPublisher,
            QuarantineCompensationHandler quarantineCompensationHandler,
            LocalDateTimeProvider localDateTimeProvider,
            StockCachePort stockCachePort,
            PaymentConfirmDlqPublisher paymentConfirmDlqPublisher,
            StockOutboxRepository stockOutboxRepository,
            ObjectMapper objectMapper,
            @Value("${payment.event-dedupe.lease-ttl:PT5M}") Duration leaseTtl,
            @Value("${payment.event-dedupe.ttl:P8D}") Duration longTtl,
            PaymentCommandUseCase paymentCommandUseCase) {
        this.paymentEventRepository = paymentEventRepository;
        this.eventDedupeStore = eventDedupeStore;
        this.applicationEventPublisher = applicationEventPublisher;
        this.quarantineCompensationHandler = quarantineCompensationHandler;
        this.localDateTimeProvider = localDateTimeProvider;
        this.stockCachePort = stockCachePort;
        this.paymentConfirmDlqPublisher = paymentConfirmDlqPublisher;
        this.stockOutboxRepository = stockOutboxRepository;
        this.objectMapper = objectMapper;
        this.leaseTtl = leaseTtl;
        this.longTtl = longTtl;
        this.paymentCommandUseCase = paymentCommandUseCase;
    }

    /**
     * two-phase lease 진입점:
     * <ol>
     *   <li>markWithLease(shortTtl) — 처리 권한 획득. false 면 다른 consumer 가 처리 중 → skip.</li>
     *   <li>processMessage 성공 → extendLease(longTtl).</li>
     *   <li>processMessage 실패 → remove. remove false 면 DLQ 전송 후 예외 재전파.</li>
     * </ol>
     */
    @Transactional(timeout = 5)
    public void handle(ConfirmedEventMessage message) {
        if (!eventDedupeStore.markWithLease(message.eventUuid(), leaseTtl)) {
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_DEDUPE,
                    () -> "orderId=" + message.orderId() + " eventUuid=" + message.eventUuid());
            return;
        }

        // 실패 시 dedupe 기록을 제거해 재컨슘을 허용한다.
        // 단 remove 자체가 실패하면(예: Redis 일시 장애) dedupe 가 영구 잠겨버리므로 DLQ 로 보내 복구 경로를 남긴다.
        processMessageWithLeaseGuard(message);
    }

    /**
     * 성공 시 TTL 연장, 실패 시 remove 시도 후 예외 재전파.
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
        PaymentEvent paymentEvent = paymentEventRepository
                .findByOrderId(message.orderId())
                .orElseThrow(() -> PaymentFoundException.of(PaymentErrorCode.PAYMENT_EVENT_NOT_FOUND));

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_START,
                () -> "orderId=" + message.orderId() + " status=" + message.status()
                        + " eventUuid=" + message.eventUuid());

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
     * </ol>
     *
     * <p>stock commit 발행은 TX 내부에서 stock_outbox INSERT + {@link StockOutboxReadyEvent} 만 한다.
     * 실 Kafka publish 는 AFTER_COMMIT 리스너가 비동기로 처리하므로 여기서는 KafkaTemplate.send 를 직접 부르지 않는다.
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

        LocalDateTime now = localDateTimeProvider.now();
        for (PaymentOrder order : paymentEvent.getPaymentOrderList()) {
            StockOutbox outbox = buildStockCommitOutbox(paymentEvent, order, now);
            StockOutbox saved = stockOutboxRepository.save(outbox);
            applicationEventPublisher.publishEvent(new StockOutboxReadyEvent(saved.getId()));
        }

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_DONE,
                () -> "orderId=" + paymentEvent.getOrderId());
    }

    private StockOutbox buildStockCommitOutbox(PaymentEvent paymentEvent, PaymentOrder order, LocalDateTime now) {
        Instant occurredAt = localDateTimeProvider.nowInstant();
        return StockOutboxFactory.buildStockCommitOutbox(paymentEvent, order, occurredAt, longTtl, now, objectMapper);
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
     * FAILED 결과 처리. 상태 전이는 PaymentCommandUseCase 위임. 재고 복원은 redis-stock 캐시에
     * 한정 — 각 PaymentOrder 별 stockCachePort.increment 호출. product RDB 는 애초에 차감되지
     * 않았으므로 복원 메시지(stock.events.restore) 발행은 하지 않는다.
     *
     * <p>race 방어 가드: markWithLease 는 같은 eventUuid 만 보호한다. IN_PROGRESS self-loop retry
     * 활성화 후 다른 eventUuid 로 같은 orderId 결과가 두 번 도착하면 두 번 진입 가능 → 보상 중복 →
     * redis-stock 발산. paymentEvent 가 이미 종결(isTerminal)이면 보상과 상태 전이를 모두 skip 한다.
     */
    private void handleFailed(PaymentEvent paymentEvent, String reasonCode) {
        if (paymentEvent.getStatus().isTerminal()) {
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_FAILED_NOOP,
                    () -> "orderId=" + paymentEvent.getOrderId()
                            + " 이미 종결 status=" + paymentEvent.getStatus());
            return;
        }

        paymentCommandUseCase.markPaymentAsFail(paymentEvent, reasonCode);

        compensateStockCache(paymentEvent, reasonCode);

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_FAILED,
                () -> "orderId=" + paymentEvent.getOrderId() + " reasonCode=" + reasonCode);
    }

    /**
     * QUARANTINED 결과 처리. 격리도 결제 미성립이므로 redis-stock 캐시는 보상한다 (선차감 분량 복원).
     * product RDB 는 변경되지 않는다 — 격리 사유 조사·admin 처리는 별도 경로.
     *
     * <p>race 방어 가드: handleFailed 와 동일한 이유로 paymentEvent 가 이미 종결(isTerminal)이면
     * 보상과 quarantineCompensationHandler 호출을 모두 skip 한다.
     */
    private void handleQuarantined(PaymentEvent paymentEvent, String reasonCode) {
        if (paymentEvent.getStatus().isTerminal()) {
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_QUARANTINED_NOOP,
                    () -> "orderId=" + paymentEvent.getOrderId()
                            + " 이미 종결 status=" + paymentEvent.getStatus());
            return;
        }

        compensateStockCache(paymentEvent, reasonCode);

        quarantineCompensationHandler.handle(
                paymentEvent.getOrderId(),
                reasonCode
        );

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_QUARANTINED,
                () -> "orderId=" + paymentEvent.getOrderId() + " reasonCode=" + reasonCode);
    }

    /**
     * Redis 선차감 캐시 보상. 각 PaymentOrder 별로 increment.
     * INCR 자체 실패는 LogFmt.error 후 다음 order 로 진행 — 원본 흐름 차단 금지.
     */
    private void compensateStockCache(PaymentEvent paymentEvent, String reasonCode) {
        for (PaymentOrder order : paymentEvent.getPaymentOrderList()) {
            try {
                stockCachePort.increment(order.getProductId(), order.getQuantity());
            } catch (RuntimeException e) {
                LogFmt.error(log, LogDomain.PAYMENT, EventType.STOCK_COMPENSATE_FAIL,
                        () -> "orderId=" + paymentEvent.getOrderId()
                                + " productId=" + order.getProductId()
                                + " qty=" + order.getQuantity()
                                + " reasonCode=" + reasonCode
                                + " error=" + e.getMessage());
            }
        }
    }

}
