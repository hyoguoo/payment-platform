package com.hyoguoo.paymentplatform.payment.application.usecase;

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
            log.info("PaymentConfirmResultUseCase: 중복 eventUUID — no-op orderId={} eventUuid={}",
                    message.orderId(), message.eventUuid());
            return;
        }

        // 2단: orderId로 PaymentEvent 조회
        PaymentEvent paymentEvent = paymentEventRepository
                .findByOrderId(message.orderId())
                .orElseThrow(() -> PaymentFoundException.of(PaymentErrorCode.PAYMENT_EVENT_NOT_FOUND));

        log.info("PaymentConfirmResultUseCase: 처리 시작 orderId={} status={} eventUuid={}",
                message.orderId(), message.status(), message.eventUuid());

        // 3단: status별 분기
        switch (message.status()) {
            case "APPROVED" -> handleApproved(paymentEvent);
            case "FAILED" -> handleFailed(paymentEvent, message.reasonCode());
            case "QUARANTINED" -> handleQuarantined(paymentEvent, message.reasonCode());
            default -> log.warn("PaymentConfirmResultUseCase: 알 수 없는 status={} orderId={}",
                    message.status(), message.orderId());
        }
    }

    private void handleApproved(PaymentEvent paymentEvent) {
        paymentEvent.done(LocalDateTime.now(), LocalDateTime.now());
        paymentEventRepository.saveOrUpdate(paymentEvent);

        // stock.events.commit 발행: 각 주문 상품별 1건씩
        for (PaymentOrder order : paymentEvent.getPaymentOrderList()) {
            stockCommitEventPublisherPort.publish(order.getProductId(), order.getQuantity(), paymentEvent.getOrderId());
        }

        log.info("PaymentConfirmResultUseCase: DONE 전이 완료 orderId={}", paymentEvent.getOrderId());
    }

    private void handleFailed(PaymentEvent paymentEvent, String reasonCode) {
        paymentEvent.fail(reasonCode, LocalDateTime.now());
        paymentEventRepository.saveOrUpdate(paymentEvent);

        // stock.events.restore 발행: 주문 상품 ID 목록
        List<Long> productIds = paymentEvent.getPaymentOrderList().stream()
                .map(PaymentOrder::getProductId)
                .toList();
        stockRestoreEventPublisherPort.publish(paymentEvent.getOrderId(), productIds);

        log.info("PaymentConfirmResultUseCase: FAILED 전이 + restore 발행 완료 orderId={}", paymentEvent.getOrderId());
    }

    private void handleQuarantined(PaymentEvent paymentEvent, String reasonCode) {
        // QUARANTINED 상태 전이는 handler 내부 책임 — consumer는 위임만
        quarantineCompensationHandler.handle(
                paymentEvent.getOrderId(),
                reasonCode,
                QuarantineCompensationHandler.QuarantineEntry.FCG
        );

        log.info("PaymentConfirmResultUseCase: QUARANTINED 위임 완료 orderId={}", paymentEvent.getOrderId());
    }
}
