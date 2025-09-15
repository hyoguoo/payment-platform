package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmGatewayCommand;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayPort;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentCreatedEvent;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentRetryAttemptedEvent;
import com.hyoguoo.paymentplatform.payment.domain.event.PaymentStatusChangedEvent;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentTossRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentProcessorUseCase {

    private final PaymentEventRepository paymentEventRepository;
    private final PaymentGatewayPort paymentGatewayPort;
    private final LocalDateTimeProvider localDateTimeProvider;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public PaymentEvent executePayment(PaymentEvent paymentEvent, String paymentKey) {
        PaymentEventStatus previousStatus = paymentEvent.getStatus();
        LocalDateTime executedAt = localDateTimeProvider.now();

        paymentEvent.execute(paymentKey, executedAt);
        PaymentEvent savedEvent = paymentEventRepository.saveOrUpdate(paymentEvent);

        publishPaymentEvent(
                savedEvent,
                previousStatus,
                "execution started",
                "payment key: " + paymentKey
        );

        return savedEvent;
    }

    @Transactional
    public PaymentEvent markPaymentAsDone(PaymentEvent paymentEvent, LocalDateTime approvedAt) {
        PaymentEventStatus previousStatus = paymentEvent.getStatus();

        paymentEvent.done(approvedAt);
        PaymentEvent savedEvent = paymentEventRepository.saveOrUpdate(paymentEvent);

        publishStatusChangedEvent(
                savedEvent,
                previousStatus,
                "Payment successfully completed at " + approvedAt
        );

        return savedEvent;
    }

    @Transactional
    public PaymentEvent markPaymentAsFail(PaymentEvent paymentEvent, String failureReason) {
        PaymentEventStatus previousStatus = paymentEvent.getStatus();

        paymentEvent.fail();
        PaymentEvent savedEvent = paymentEventRepository.saveOrUpdate(paymentEvent);

        publishStatusChangedEvent(
                savedEvent,
                previousStatus,
                failureReason
        );

        return savedEvent;
    }

    @Transactional
    public PaymentEvent markPaymentAsUnknown(PaymentEvent paymentEvent, String reason) {
        PaymentEventStatus previousStatus = paymentEvent.getStatus();

        paymentEvent.unknown();
        PaymentEvent savedEvent = paymentEventRepository.saveOrUpdate(paymentEvent);

        publishStatusChangedEvent(
                savedEvent,
                previousStatus,
                reason
        );

        return savedEvent;
    }

    @Transactional
    public void increaseRetryCount(PaymentEvent paymentEvent, String retryReason) {
        PaymentEventStatus previousStatus = paymentEvent.getStatus();

        paymentEvent.increaseRetryCount();
        PaymentEvent savedEvent = paymentEventRepository.saveOrUpdate(paymentEvent);

        publishRetryAttemptedEvent(
                savedEvent,
                previousStatus,
                retryReason
        );
    }

    public void validateCompletionStatus(
            PaymentEvent paymentEvent,
            PaymentConfirmCommand paymentConfirmCommand
    ) {
        TossPaymentInfo tossPaymentInfo = paymentGatewayPort.getPaymentInfoByOrderId(
                paymentConfirmCommand.getOrderId()
        );

        paymentEvent.validateCompletionStatus(paymentConfirmCommand, tossPaymentInfo);
    }

    public TossPaymentInfo confirmPaymentWithGateway(PaymentConfirmCommand paymentConfirmCommand)
            throws PaymentTossRetryableException, PaymentTossNonRetryableException {
        TossConfirmGatewayCommand tossConfirmGatewayCommand = TossConfirmGatewayCommand.builder()
                .orderId(paymentConfirmCommand.getOrderId())
                .paymentKey(paymentConfirmCommand.getPaymentKey())
                .amount(paymentConfirmCommand.getAmount())
                .idempotencyKey(paymentConfirmCommand.getOrderId())
                .build();

        TossPaymentInfo tossPaymentInfo = paymentGatewayPort.confirmPayment(
                tossConfirmGatewayCommand
        );

        PaymentConfirmResultStatus paymentConfirmResultStatus = tossPaymentInfo.getPaymentConfirmResultStatus();

        return switch (paymentConfirmResultStatus) {
            case PaymentConfirmResultStatus.SUCCESS -> tossPaymentInfo;
            case PaymentConfirmResultStatus.RETRYABLE_FAILURE ->
                    throw PaymentTossRetryableException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR);
            case PaymentConfirmResultStatus.NON_RETRYABLE_FAILURE ->
                    throw PaymentTossNonRetryableException.of(PaymentErrorCode.TOSS_NON_RETRYABLE_ERROR);
        };
    }

    /**
     * 통합된 이벤트 발행 메서드 - 모든 상태 변경 이벤트를 처리
     */
    private void publishPaymentEvent(PaymentEvent savedEvent,
                                    PaymentEventStatus previousStatus,
                                    String action,
                                    String details) {
        String reason = generateEventReason(action, details, savedEvent);
        
        // 재시도인 경우와 일반 상태 변경을 구분
        if (isRetryEvent(savedEvent, previousStatus)) {
            publishRetryAttemptedEvent(savedEvent, previousStatus, reason);
        } else if (previousStatus == null) {
            // 최초 생성 이벤트
            publishPaymentCreatedEvent(savedEvent, reason);
        } else {
            // 일반 상태 변경 이벤트
            publishStatusChangedEvent(savedEvent, previousStatus, reason);
        }
    }
    
    private boolean isRetryEvent(PaymentEvent event, PaymentEventStatus previousStatus) {
        return event.getRetryCount() > 0 && 
               previousStatus == event.getStatus();
    }
    
    private String generateEventReason(String action, String details, PaymentEvent event) {
        if (details != null) {
            return String.format("Payment %s: %s", action, details);
        }
        return String.format("Payment %s for order: %s", action, event.getOrderName());
    }
    
    private void publishPaymentCreatedEvent(PaymentEvent savedEvent, String reason) {
        eventPublisher.publishEvent(
                PaymentCreatedEvent.of(
                        savedEvent.getId(),
                        savedEvent.getOrderId(),
                        savedEvent.getStatus(),
                        reason,
                        LocalDateTime.now()
                )
        );
    }
    
    private void publishStatusChangedEvent(
            PaymentEvent savedEvent,
            PaymentEventStatus previousStatus,
            String reason
    ) {
        eventPublisher.publishEvent(
                PaymentStatusChangedEvent.of(
                        savedEvent.getId(),
                        savedEvent.getOrderId(),
                        previousStatus,
                        savedEvent.getStatus(),
                        reason,
                        LocalDateTime.now()
                )
        );
    }

    private void publishRetryAttemptedEvent(
            PaymentEvent savedEvent,
            PaymentEventStatus previousStatus,
            String reason
    ) {
        eventPublisher.publishEvent(
                PaymentRetryAttemptedEvent.of(
                        savedEvent.getId(),
                        savedEvent.getOrderId(),
                        previousStatus,
                        savedEvent.getStatus(),
                        reason,
                        savedEvent.getRetryCount(),
                        LocalDateTime.now()
                )
        );
    }
}
