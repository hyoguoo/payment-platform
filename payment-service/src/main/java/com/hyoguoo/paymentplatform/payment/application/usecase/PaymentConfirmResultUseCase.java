package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.port.out.EventDedupeStore;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockCommitEventPublisherPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.StockRestoreEventPublisherPort;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.infrastructure.messaging.consumer.dto.ConfirmedEventMessage;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * payment.events.confirmed 소비 후 결제 상태 분기 use-case.
 * ADR-04(2단 멱등성): eventUUID dedupe 선행, 처리 주체 결정.
 * ADR-14: stock 이벤트 발행(commit/restore) 담당.
 *
 * <p>상태 분기:
 * <ul>
 *   <li>APPROVED → PaymentEvent DONE 전이 + stock.events.commit 발행</li>
 *   <li>FAILED → PaymentEvent FAILED 전이 + stock.events.restore 발행</li>
 *   <li>QUARANTINED → QuarantineCompensationHandler.handle(FCG 진입점) 위임</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentConfirmResultUseCase {

    private final PaymentEventRepository paymentEventRepository;
    private final EventDedupeStore eventDedupeStore;
    private final StockCommitEventPublisherPort stockCommitEventPublisherPort;
    private final StockRestoreEventPublisherPort stockRestoreEventPublisherPort;
    private final QuarantineCompensationHandler quarantineCompensationHandler;

    @Transactional
    public void handle(ConfirmedEventMessage message) {
        // 1단: eventUUID dedupe
        if (!eventDedupeStore.markSeen(message.eventUuid())) {
            LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_DEDUPE,
                    () -> "orderId=" + message.orderId() + " eventUuid=" + message.eventUuid());
            return;
        }

        // TX 경계 불일치 방어: stock Kafka publish 실패로 TX 롤백 시 dedupe도 같이 되돌려야
        // 재컨슘 경로에서 영구 정체를 방지한다.
        try {
            processMessage(message);
        } catch (RuntimeException e) {
            eventDedupeStore.remove(message.eventUuid());
            throw e;
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
            case "APPROVED" -> handleApproved(paymentEvent);
            case "FAILED" -> handleFailed(paymentEvent, message.reasonCode());
            case "QUARANTINED" -> handleQuarantined(paymentEvent, message.reasonCode());
            default -> LogFmt.warn(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_UNKNOWN_STATUS,
                    () -> "orderId=" + message.orderId() + " status=" + message.status());
        }
    }

    private void handleApproved(PaymentEvent paymentEvent) {
        paymentEvent.done(LocalDateTime.now(), LocalDateTime.now());
        paymentEventRepository.saveOrUpdate(paymentEvent);

        // stock.events.commit 발행: 각 주문 상품별 1건씩
        for (PaymentOrder order : paymentEvent.getPaymentOrderList()) {
            stockCommitEventPublisherPort.publish(order.getProductId(), order.getQuantity(), paymentEvent.getOrderId());
        }

        LogFmt.info(log, LogDomain.PAYMENT, EventType.PAYMENT_CONFIRM_RESULT_DONE,
                () -> "orderId=" + paymentEvent.getOrderId());
    }

    private void handleFailed(PaymentEvent paymentEvent, String reasonCode) {
        paymentEvent.fail(reasonCode, LocalDateTime.now());
        paymentEventRepository.saveOrUpdate(paymentEvent);

        // stock.events.restore 발행: 주문 상품 ID 목록
        List<Long> productIds = paymentEvent.getPaymentOrderList().stream()
                .map(PaymentOrder::getProductId)
                .toList();
        stockRestoreEventPublisherPort.publish(paymentEvent.getOrderId(), productIds);

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
}
