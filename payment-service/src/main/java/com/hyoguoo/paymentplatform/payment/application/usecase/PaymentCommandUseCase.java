package com.hyoguoo.paymentplatform.payment.application.usecase;

import com.hyoguoo.paymentplatform.core.common.aspect.annotation.PublishDomainEvent;
import com.hyoguoo.paymentplatform.core.common.aspect.annotation.Reason;
import com.hyoguoo.paymentplatform.core.common.metrics.PaymentQuarantineMetrics;
import com.hyoguoo.paymentplatform.core.common.metrics.annotation.PaymentStatusChange;
import com.hyoguoo.paymentplatform.core.common.service.port.LocalDateTimeProvider;
import com.hyoguoo.paymentplatform.payment.application.dto.request.PaymentConfirmCommand;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentEventRepository;
import com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayPort;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentGatewayInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentGatewayStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.vo.PaymentDetails;
import com.hyoguoo.paymentplatform.payment.domain.dto.vo.PaymentFailure;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayRetryableException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentCommandUseCase {

    private final PaymentEventRepository paymentEventRepository;
    private final PaymentGatewayPort paymentGatewayPort;
    private final LocalDateTimeProvider localDateTimeProvider;
    private final PaymentQuarantineMetrics paymentQuarantineMetrics;

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "IN_PROGRESS", trigger = "confirm")
    public PaymentEvent executePayment(PaymentEvent paymentEvent, String paymentKey) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.execute(paymentKey, now, now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "DONE", trigger = "auto")
    public PaymentEvent markPaymentAsDone(PaymentEvent paymentEvent, LocalDateTime approvedAt) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.done(approvedAt, now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "FAILED", trigger = "auto")
    public PaymentEvent markPaymentAsFail(PaymentEvent paymentEvent, @Reason String failureReason) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.fail(failureReason, now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "EXPIRED", trigger = "expiration")
    public PaymentEvent expirePayment(PaymentEvent paymentEvent) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.expire(now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "RETRYING", trigger = "auto")
    public PaymentEvent markPaymentAsRetrying(PaymentEvent paymentEvent) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.toRetrying(now);
        return paymentEventRepository.saveOrUpdate(paymentEvent);
    }

    @Transactional
    @PublishDomainEvent(action = "changed")
    @PaymentStatusChange(toStatus = "QUARANTINED", trigger = "auto")
    public PaymentEvent markPaymentAsQuarantined(PaymentEvent paymentEvent, @Reason String reason) {
        LocalDateTime now = localDateTimeProvider.now();
        paymentEvent.quarantine(reason, now);
        PaymentEvent saved = paymentEventRepository.saveOrUpdate(paymentEvent);
        paymentQuarantineMetrics.recordQuarantine(reason);
        return saved;
    }

    /**
     * 복구 사이클용 getStatus 위임 메서드.
     * scheduler(OutboxProcessingService)가 PaymentGatewayPort를 직접 주입하면 layer 위반이므로
     * 이 use-case를 경유한다. 예외 변환 없이 그대로 전파한다.
     */
    public PaymentStatusResult getPaymentStatusByOrderId(String orderId, PaymentGatewayType gatewayType)
            throws PaymentGatewayRetryableException, PaymentGatewayNonRetryableException {
        return paymentGatewayPort.getStatusByOrderId(orderId, gatewayType);
    }

    public PaymentGatewayInfo confirmPaymentWithGateway(PaymentConfirmCommand paymentConfirmCommand)
            throws PaymentGatewayRetryableException, PaymentGatewayNonRetryableException {
        PaymentConfirmRequest request =
                new PaymentConfirmRequest(
                        paymentConfirmCommand.getOrderId(),
                        paymentConfirmCommand.getPaymentKey(),
                        paymentConfirmCommand.getAmount(),
                        paymentConfirmCommand.getGatewayType()
                );

        PaymentConfirmResult result = paymentGatewayPort.confirm(request);

        return PaymentGatewayInfo.builder()
                .paymentKey(result.paymentKey())
                .orderId(request.orderId())
                .paymentConfirmResultStatus(result.status())
                .paymentDetails(
                        PaymentDetails.builder()
                                .totalAmount(result.amount())
                                .status(PaymentGatewayStatus.DONE)
                                .approvedAt(result.approvedAt())
                                .build()
                )
                .paymentFailure(
                        result.failure() != null ?
                                PaymentFailure.builder()
                                .code(result.failure().code())
                                .message(result.failure().message())
                                .build() : null
                )
                .build();
    }

}
