package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.payment.core.common.log.EventType;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.payment.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.event.StockOutboxReadyEvent;
import com.hyoguoo.paymentplatform.payment.application.util.StockOutboxFactory;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * payment.events.confirmed 소비 후 결제 상태 분기 use-case (SCR-6 재작성).
 *
 * <p>dedupe 책임 0 — eventUuid dedupe lease 및 DLQ 직접 호출 제거.
 * retry / DLQ 정책은 Spring Kafka {@code DefaultErrorHandler} (SCR-8) 가 담당한다.
 *
 * <p>stock 이벤트 발행은 ApplicationEvent 로 위임해 TX 경계와 직접 결합하지 않는다.
 *
 * <p>상태 분기:
 * <ul>
 *   <li>APPROVED → PaymentCommandUseCase.markPaymentAsDone 위임 + stock_outbox INSERT + StockOutboxReadyEvent</li>
 *   <li>FAILED → compensateAtomic (보상 먼저) → markPaymentAsFail (RDB 나중). RuntimeException 은 그대로 throw.</li>
 *   <li>QUARANTINED → compensateAtomic (보상 먼저) → quarantineCompensationHandler. 기존 순서 유지.</li>
 * </ul>
 *
 * <p>호출 순서 근거 (FAILED): DECISION §6 crash 표 — 구 순서(RDB → 보상)는 RDB commit 직후/보상 전 crash 시
 * isTerminal=true 로 재배달 noop → 보상 누락 silent loss. 새 순서(보상 → RDB)는 crash 후 재배달 시
 * compensateAtomic 의 dedup token 이 ALREADY_DONE 을 반환 → markPaymentAsFail 재진행 → 정합 보장.
 */
@Slf4j
@Service
public class PaymentConfirmResultUseCase {

    /** stock_outbox expiresAt 계산용 TTL. Kafka retention(7 일) + 복구 버퍼(1 일) = 8 일. */
    private static final Duration STOCK_OUTBOX_TTL = Duration.ofDays(8);

    private final PaymentEventRepository paymentEventRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final QuarantineCompensationHandler quarantineCompensationHandler;
    private final LocalDateTimeProvider localDateTimeProvider;
    private final StockCachePort stockCachePort;
    private final StockOutboxRepository stockOutboxRepository;
    private final ObjectMapper objectMapper;
    /**
     * 상태 전이 위임 use-case. self-invocation 으로 호출하면
     * {@code @PublishDomainEvent} / {@code @PaymentStatusChange} AOP 가 적용되지 않으므로 외부 빈을 통해 호출해야 한다.
     */
    private final PaymentCommandUseCase paymentCommandUseCase;

    public PaymentConfirmResultUseCase(
            PaymentEventRepository paymentEventRepository,
            ApplicationEventPublisher applicationEventPublisher,
            QuarantineCompensationHandler quarantineCompensationHandler,
            LocalDateTimeProvider localDateTimeProvider,
            StockCachePort stockCachePort,
            StockOutboxRepository stockOutboxRepository,
            ObjectMapper objectMapper,
            PaymentCommandUseCase paymentCommandUseCase) {
        this.paymentEventRepository = paymentEventRepository;
        this.applicationEventPublisher = applicationEventPublisher;
        this.quarantineCompensationHandler = quarantineCompensationHandler;
        this.localDateTimeProvider = localDateTimeProvider;
        this.stockCachePort = stockCachePort;
        this.stockOutboxRepository = stockOutboxRepository;
        this.objectMapper = objectMapper;
        this.paymentCommandUseCase = paymentCommandUseCase;
    }

    /**
     * 메시지 처리 진입점. dedupe lease 없이 직접 processMessage 위임.
     * RuntimeException 은 Spring Kafka DefaultErrorHandler(SCR-8) 가 처리한다.
     */
    @Transactional(timeout = 5)
    public void handle(ConfirmedEventMessage message) {
        processMessage(message);
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
        return StockOutboxFactory.buildStockCommitOutbox(
                paymentEvent, order, occurredAt, STOCK_OUTBOX_TTL, now, objectMapper);
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
     * isTerminal=true 로 재배달이 noop 처리돼 보상 누락 silent loss 발생.
     * 새 순서(보상 → RDB)에서 crash 후 재배달 시 compensateAtomic 의 dedup token 이
     * ALREADY_DONE 을 반환하므로 markPaymentAsFail 을 재진행해 정합이 보장된다.
     *
     * <p>race 방어 가드: paymentEvent 가 이미 종결(isTerminal)이면 보상과 상태 전이를 모두 skip 한다.
     *
     * <p>RuntimeException 은 그대로 throw — Spring Kafka DefaultErrorHandler(SCR-8) 가 retry / DLQ 책임.
     */
    private void handleFailed(PaymentEvent paymentEvent, String reasonCode) {
        if (paymentEvent.getStatus().isTerminal()) {
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_FAILED_NOOP,
                    () -> "orderId=" + paymentEvent.getOrderId()
                            + " 이미 종결 status=" + paymentEvent.getStatus());
            return;
        }

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
     * <p>race 방어 가드: paymentEvent 가 이미 종결(isTerminal)이면 보상과 quarantineCompensationHandler 호출을 모두 skip.
     *
     * <p>RuntimeException 은 그대로 throw — Spring Kafka DefaultErrorHandler(SCR-8) 가 retry / DLQ 책임.
     */
    private void handleQuarantined(PaymentEvent paymentEvent, String reasonCode) {
        if (paymentEvent.getStatus().isTerminal()) {
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_QUARANTINED_NOOP,
                    () -> "orderId=" + paymentEvent.getOrderId()
                            + " 이미 종결 status=" + paymentEvent.getStatus());
            return;
        }

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
