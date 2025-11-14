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

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_DONE = "DONE";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_FAILURE = "FAILURE";
    private static final String STATUS_ABORTED = "ABORTED";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_CANCELED = "CANCELED";
    private static final String STATUS_EXPIRED = "EXPIRED";
    private static final String STATUS_WAITING_FOR_DEPOSIT = "WAITING_FOR_DEPOSIT";
    private static final String STATUS_PARTIAL_CANCELED = "PARTIAL_CANCELED";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_UNKNOWN = "UNKNOWN";

    private static final String ERROR_PROVIDER_ERROR = "PROVIDER_ERROR";
    private static final String ERROR_FAILED_PAYMENT_INTERNAL = "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING";
    private static final String ERROR_FAILED_INTERNAL = "FAILED_INTERNAL_SYSTEM_PROCESSING";
    private static final String ERROR_UNKNOWN_PAYMENT = "UNKNOWN_PAYMENT_ERROR";
    private static final String ERROR_NETWORK = "NETWORK_ERROR";
    private static final String ERROR_PAY_PROCESS_ABORTED = "PAY_PROCESS_ABORTED";
    private static final String ERROR_TIMEOUT_PREFIX = "TIMEOUT";

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
        PaymentFailureInfo failure = createPaymentFailureInfo(tossPaymentInfo);
        PaymentConfirmResultStatus status = determineConfirmResultStatus(tossPaymentInfo, failure);

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

    private PaymentFailureInfo createPaymentFailureInfo(TossPaymentInfo tossPaymentInfo) {
        if (tossPaymentInfo.getPaymentFailure() == null) {
            return null;
        }
        return new PaymentFailureInfo(
                tossPaymentInfo.getPaymentFailure().getCode(),
                tossPaymentInfo.getPaymentFailure().getMessage(),
                isRetryable(tossPaymentInfo.getPaymentFailure().getCode())
        );
    }

    private PaymentConfirmResultStatus determineConfirmResultStatus(
            TossPaymentInfo tossPaymentInfo,
            PaymentFailureInfo failure
    ) {
        String tossStatus = tossPaymentInfo.getPaymentConfirmResultStatus().name();

        if (STATUS_SUCCESS.equals(tossStatus) || STATUS_DONE.equals(tossStatus)) {
            return PaymentConfirmResultStatus.SUCCESS;
        }

        if (failure != null && failure.isRetryable()) {
            return PaymentConfirmResultStatus.RETRYABLE_FAILURE;
        }

        return PaymentConfirmResultStatus.NON_RETRYABLE_FAILURE;
    }

    private PaymentCancelResult convertToPaymentCancelResult(
            TossPaymentInfo tossPaymentInfo,
            PaymentCancelRequest request
    ) {
        PaymentCancelResultStatus status = mapToPaymentCancelResultStatus(
                tossPaymentInfo.getPaymentConfirmResultStatus().name()
        );

        PaymentFailureInfo failure = createPaymentFailureInfo(tossPaymentInfo);

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
                        : STATUS_UNKNOWN
        );

        PaymentFailureInfo failure = createPaymentFailureInfo(tossPaymentInfo);

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

    private PaymentCancelResultStatus mapToPaymentCancelResultStatus(String tossStatus) {
        return switch (tossStatus) {
            case STATUS_DONE, STATUS_SUCCESS, STATUS_CANCELED -> PaymentCancelResultStatus.SUCCESS;
            case STATUS_FAILED, STATUS_FAILURE -> PaymentCancelResultStatus.FAILURE;
            default -> PaymentCancelResultStatus.FAILURE;
        };
    }

    private PaymentStatus mapToPaymentStatus(String tossStatus) {
        return switch (tossStatus) {
            case STATUS_DONE, STATUS_SUCCESS -> PaymentStatus.DONE;
            case STATUS_FAILED, STATUS_FAILURE, STATUS_ABORTED -> PaymentStatus.ABORTED;
            case STATUS_IN_PROGRESS, STATUS_PENDING -> PaymentStatus.IN_PROGRESS;
            case STATUS_CANCELED -> PaymentStatus.CANCELED;
            case STATUS_EXPIRED -> PaymentStatus.EXPIRED;
            case STATUS_WAITING_FOR_DEPOSIT -> PaymentStatus.WAITING_FOR_DEPOSIT;
            case STATUS_PARTIAL_CANCELED -> PaymentStatus.PARTIAL_CANCELED;
            case STATUS_READY -> PaymentStatus.READY;
            default -> PaymentStatus.UNKNOWN;
        };
    }

    private boolean isRetryable(String errorCode) {
        if (errorCode == null) {
            return false;
        }

        return errorCode.equals(ERROR_PROVIDER_ERROR) ||
                errorCode.equals(ERROR_FAILED_PAYMENT_INTERNAL) ||
                errorCode.equals(ERROR_FAILED_INTERNAL) ||
                errorCode.equals(ERROR_UNKNOWN_PAYMENT) ||
                errorCode.equals(STATUS_UNKNOWN) ||
                errorCode.equals(ERROR_NETWORK) ||
                errorCode.startsWith(ERROR_TIMEOUT_PREFIX) ||
                errorCode.equals(ERROR_PAY_PROCESS_ABORTED);
    }

    private String generateIdempotencyKey(String baseKey) {
        return baseKey + "_" + System.currentTimeMillis();
    }
}
