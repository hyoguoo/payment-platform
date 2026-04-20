package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentStatusResult.StatusType;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentLoadUseCase;
import com.hyoguoo.paymentplatform.payment.application.usecase.PaymentOutboxUseCase;
import com.hyoguoo.paymentplatform.payment.domain.PaymentEvent;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentStatusServiceImpl implements PaymentStatusService {

    private final PaymentLoadUseCase paymentLoadUseCase;
    private final PaymentOutboxUseCase paymentOutboxUseCase;

    @Override
    public PaymentStatusResult getPaymentStatus(String orderId) {
        return paymentOutboxUseCase.findActiveOutboxStatus(orderId)
                .map(status -> buildFromOutbox(orderId, status))
                .orElseGet(() -> buildFromEvent(paymentLoadUseCase.getPaymentEventByOrderId(orderId)));
    }

    private PaymentStatusResult buildFromOutbox(String orderId, PaymentOutboxStatus outboxStatus) {
        StatusType statusType = outboxStatus == PaymentOutboxStatus.PENDING
                ? StatusType.PENDING
                : StatusType.PROCESSING;
        return PaymentStatusResult.builder()
                .orderId(orderId)
                .status(statusType)
                .approvedAt(null)
                .build();
    }

    private PaymentStatusResult buildFromEvent(PaymentEvent paymentEvent) {
        StatusType statusType = mapEventStatus(paymentEvent.getStatus());
        return PaymentStatusResult.builder()
                .orderId(paymentEvent.getOrderId())
                .status(statusType)
                .approvedAt(paymentEvent.getApprovedAt())
                .build();
    }

    private StatusType mapEventStatus(PaymentEventStatus eventStatus) {
        return switch (eventStatus) {
            case DONE -> StatusType.DONE;
            case FAILED -> StatusType.FAILED;
            default -> StatusType.PROCESSING;
        };
    }
}
