package com.hyoguoo.paymentplatform.payment.application;

import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.application.dto.response.PaymentStatusResult.StatusType;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentStatusQueryPort;
import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentStatusSnapshot;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentEventStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentOutboxStatus;
import com.hyoguoo.paymentplatform.payment.exception.PaymentFoundException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.presentation.port.PaymentStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentStatusServiceImpl implements PaymentStatusService {

    private final PaymentStatusQueryPort paymentStatusQueryPort;

    @Override
    public PaymentStatusResult getPaymentStatus(String orderId) {
        return paymentStatusQueryPort.findActiveOutboxStatus(orderId)
                .map(status -> buildFromOutbox(orderId, status))
                .orElseGet(() -> buildFromSnapshot(orderId));
    }

    private PaymentStatusResult buildFromOutbox(String orderId, PaymentOutboxStatus outboxStatus) {
        StatusType statusType = outboxStatus.isClaimable()
                ? StatusType.PENDING
                : StatusType.PROCESSING;
        return PaymentStatusResult.builder()
                .orderId(orderId)
                .status(statusType)
                .approvedAt(null)
                .build();
    }

    private PaymentStatusResult buildFromSnapshot(String orderId) {
        PaymentStatusSnapshot snapshot = paymentStatusQueryPort.findStatusSnapshot(orderId)
                .orElseThrow(() -> PaymentFoundException.of(PaymentErrorCode.PAYMENT_EVENT_NOT_FOUND));
        StatusType statusType = mapEventStatus(snapshot.status());
        return PaymentStatusResult.builder()
                .orderId(snapshot.orderId())
                .status(statusType)
                .approvedAt(snapshot.approvedAt())
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
