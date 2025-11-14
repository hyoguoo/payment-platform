package com.hyoguoo.paymentplatform.payment.infrastructure.gateway.toss;

import com.hyoguoo.paymentplatform.payment.application.dto.request.TossCancelGatewayCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmGatewayCommand;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentFailureInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.TossPaymentInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentCancelResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentStatus;
import com.hyoguoo.paymentplatform.payment.infrastructure.PaymentInfrastructureMapper;
import com.hyoguoo.paymentplatform.payment.infrastructure.gateway.PaymentGatewayStrategy;
import com.hyoguoo.paymentplatform.payment.infrastructure.gateway.PaymentGatewayType;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.PaymentGatewayInternalReceiver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TossPaymentGatewayStrategy implements PaymentGatewayStrategy {

    private final PaymentGatewayInternalReceiver paymentGatewayInternalReceiver;

    @Override
    public boolean supports(PaymentGatewayType type) {
        return type == PaymentGatewayType.TOSS;
    }

    @Override
    public PaymentConfirmResult confirm(PaymentConfirmRequest request) {
        TossConfirmGatewayCommand tossCommand = TossConfirmGatewayCommand.builder()
                .orderId(request.orderId())
                .paymentKey(request.paymentKey())
                .amount(request.amount())
                .idempotencyKey(generateIdempotencyKey(request.orderId()))
                .build();

        TossPaymentInfo tossPaymentInfo = PaymentInfrastructureMapper.toTossPaymentInfo(
                paymentGatewayInternalReceiver.confirmPayment(
                        PaymentInfrastructureMapper.toTossConfirmRequest(tossCommand)
                )
        );

        return convertToPaymentConfirmResult(tossPaymentInfo, request);
    }

    @Override
    public PaymentCancelResult cancel(PaymentCancelRequest request) {
        TossCancelGatewayCommand tossCommand = TossCancelGatewayCommand.builder()
                .paymentKey(request.paymentKey())
                .cancelReason(request.cancelReason())
                .idempotencyKey(generateIdempotencyKey(request.paymentKey()))
                .build();

        TossPaymentInfo tossPaymentInfo = PaymentInfrastructureMapper.toTossPaymentInfo(
                paymentGatewayInternalReceiver.cancelPayment(
                        PaymentInfrastructureMapper.toTossCancelRequest(tossCommand)
                )
        );

        return convertToPaymentCancelResult(tossPaymentInfo, request);
    }

    @Override
    public PaymentStatusResult getStatus(String paymentKey) {
        TossPaymentInfo tossPaymentInfo = PaymentInfrastructureMapper.toTossPaymentInfo(
                paymentGatewayInternalReceiver.getPaymentInfoByOrderId(paymentKey)
        );

        return convertToPaymentStatusResult(tossPaymentInfo);
    }

    private PaymentConfirmResult convertToPaymentConfirmResult(
            TossPaymentInfo tossPaymentInfo,
            PaymentConfirmRequest request
    ) {
        PaymentConfirmResultStatus status = mapToPaymentConfirmResultStatus(
                tossPaymentInfo.getPaymentConfirmResultStatus().name()
        );

        PaymentFailureInfo failure = tossPaymentInfo.getPaymentFailure() != null
                ? new PaymentFailureInfo(
                        tossPaymentInfo.getPaymentFailure().getCode(),
                        tossPaymentInfo.getPaymentFailure().getMessage(),
                        isRetryable(tossPaymentInfo.getPaymentFailure().getCode())
                )
                : null;

        return new PaymentConfirmResult(
                status,
                tossPaymentInfo.getPaymentKey(),
                request.orderId(),
                request.amount(),
                tossPaymentInfo.getPaymentDetails() != null
                        ? tossPaymentInfo.getPaymentDetails().getApprovedAt()
                        : null,
                failure
        );
    }

    private PaymentCancelResult convertToPaymentCancelResult(
            TossPaymentInfo tossPaymentInfo,
            PaymentCancelRequest request
    ) {
        PaymentCancelResultStatus status = mapToPaymentCancelResultStatus(
                tossPaymentInfo.getPaymentConfirmResultStatus().name()
        );

        PaymentFailureInfo failure = tossPaymentInfo.getPaymentFailure() != null
                ? new PaymentFailureInfo(
                        tossPaymentInfo.getPaymentFailure().getCode(),
                        tossPaymentInfo.getPaymentFailure().getMessage(),
                        isRetryable(tossPaymentInfo.getPaymentFailure().getCode())
                )
                : null;

        return new PaymentCancelResult(
                status,
                tossPaymentInfo.getPaymentKey(),
                tossPaymentInfo.getPaymentDetails() != null
                        ? tossPaymentInfo.getPaymentDetails().getApprovedAt()
                        : null,
                request.amount(),
                failure
        );
    }

    private PaymentStatusResult convertToPaymentStatusResult(TossPaymentInfo tossPaymentInfo) {
        PaymentStatus status = mapToPaymentStatus(
                tossPaymentInfo.getPaymentDetails() != null
                        ? tossPaymentInfo.getPaymentDetails().getStatus().name()
                        : "UNKNOWN"
        );

        PaymentFailureInfo failure = tossPaymentInfo.getPaymentFailure() != null
                ? new PaymentFailureInfo(
                        tossPaymentInfo.getPaymentFailure().getCode(),
                        tossPaymentInfo.getPaymentFailure().getMessage(),
                        isRetryable(tossPaymentInfo.getPaymentFailure().getCode())
                )
                : null;

        return new PaymentStatusResult(
                tossPaymentInfo.getPaymentKey(),
                tossPaymentInfo.getOrderId(),
                status,
                tossPaymentInfo.getPaymentDetails() != null
                        ? tossPaymentInfo.getPaymentDetails().getTotalAmount()
                        : null,
                tossPaymentInfo.getPaymentDetails() != null
                        ? tossPaymentInfo.getPaymentDetails().getApprovedAt()
                        : null,
                failure
        );
    }

    private PaymentConfirmResultStatus mapToPaymentConfirmResultStatus(String tossStatus) {
        return switch (tossStatus) {
            case "DONE", "SUCCESS" -> PaymentConfirmResultStatus.SUCCESS;
            case "FAILED", "FAILURE", "ABORTED" -> PaymentConfirmResultStatus.NON_RETRYABLE_FAILURE;
            case "IN_PROGRESS", "PENDING" -> PaymentConfirmResultStatus.RETRYABLE_FAILURE;
            default -> PaymentConfirmResultStatus.NON_RETRYABLE_FAILURE;
        };
    }

    private PaymentCancelResultStatus mapToPaymentCancelResultStatus(String tossStatus) {
        return switch (tossStatus) {
            case "DONE", "SUCCESS", "CANCELED" -> PaymentCancelResultStatus.SUCCESS;
            case "FAILED", "FAILURE" -> PaymentCancelResultStatus.FAILURE;
            default -> PaymentCancelResultStatus.FAILURE;
        };
    }

    private PaymentStatus mapToPaymentStatus(String tossStatus) {
        return switch (tossStatus) {
            case "DONE", "SUCCESS" -> PaymentStatus.DONE;
            case "FAILED", "FAILURE", "ABORTED" -> PaymentStatus.ABORTED;
            case "IN_PROGRESS", "PENDING" -> PaymentStatus.IN_PROGRESS;
            case "CANCELED" -> PaymentStatus.CANCELED;
            case "EXPIRED" -> PaymentStatus.EXPIRED;
            case "WAITING_FOR_DEPOSIT" -> PaymentStatus.WAITING_FOR_DEPOSIT;
            case "PARTIAL_CANCELED" -> PaymentStatus.PARTIAL_CANCELED;
            case "READY" -> PaymentStatus.READY;
            default -> PaymentStatus.UNKNOWN;
        };
    }

    private boolean isRetryable(String errorCode) {
        return errorCode != null && (
                errorCode.startsWith("TIMEOUT") ||
                errorCode.startsWith("NETWORK") ||
                errorCode.equals("PAY_PROCESS_ABORTED")
        );
    }

    private String generateIdempotencyKey(String baseKey) {
        return baseKey + "_" + System.currentTimeMillis();
    }
}
