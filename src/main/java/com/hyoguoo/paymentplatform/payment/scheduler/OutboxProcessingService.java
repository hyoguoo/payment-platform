package com.hyoguoo.paymentplatform.payment.scheduler;

import com.hyoguoo.paymentplatform.core.common.log.EventType;
import com.hyoguoo.paymentplatform.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentCommandUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentTransactionCoordinator;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.PaymentOutbox;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentGatewayInfo;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxProcessingService {

    private final PaymentOutboxUseCase paymentOutboxUseCase;
    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentCommandUseCase paymentCommandUseCase;
    private final PaymentTransactionCoordinator transactionCoordinator;

    public void process(String orderId) {
        // Step 1: atomic UPDATE WHERE status='PENDING' → IN_FLIGHT
        Optional<PaymentOutbox> outboxOpt = paymentOutboxUseCase.claimToInFlight(orderId);
        if (outboxOpt.isEmpty()) {
            return;
        }
        PaymentOutbox outbox = outboxOpt.orElseThrow();

        PaymentEvent paymentEvent = loadPaymentEvent(orderId, outbox);
        if (paymentEvent == null) {
            return;
        }

        try {
            // Step 2: Toss API 호출 (트랜잭션 밖)
            PaymentConfirmCommand command = PaymentConfirmCommand.builder()
                    .userId(paymentEvent.getBuyerId())
                    .orderId(orderId)
                    .paymentKey(paymentEvent.getPaymentKey())
                    .amount(paymentEvent.getTotalAmount())
                    .build();
            PaymentGatewayInfo gatewayInfo = paymentCommandUseCase.confirmPaymentWithGateway(command);

            // Step 3-A: 성공 처리 (별도 트랜잭션)
            transactionCoordinator.executePaymentSuccessCompletionWithOutbox(
                    paymentEvent, gatewayInfo.getPaymentDetails().getApprovedAt(), outbox);

        } catch (PaymentTossNonRetryableException e) {
            // Step 3-B: 비재시도 실패 — 보상 트랜잭션
            LogFmt.error(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);
            transactionCoordinator.executePaymentFailureCompensationWithOutbox(
                    paymentEvent, paymentEvent.getPaymentOrderList(), e.getMessage(), outbox);

        } catch (PaymentTossRetryableException e) {
            // Step 3-C: 재시도 가능 — retryCount 증가 또는 소진 시 보상 트랜잭션
            LogFmt.warn(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);
            boolean exhausted = paymentOutboxUseCase.incrementRetryOrFail(orderId, outbox);
            if (exhausted) {
                transactionCoordinator.executePaymentFailureCompensationWithOutbox(
                        paymentEvent, paymentEvent.getPaymentOrderList(), e.getMessage(), outbox);
            }
        }
    }

    private PaymentEvent loadPaymentEvent(String orderId, PaymentOutbox outbox) {
        try {
            return paymentLoadUseCase.getPaymentEventByOrderId(orderId);
        } catch (Exception e) {
            LogFmt.error(log, LogDomain.PAYMENT, EventType.EXCEPTION, e::getMessage);
            paymentOutboxUseCase.incrementRetryOrFail(orderId, outbox);
            return null;
        }
    }
}
