package com.hyoguoo.paymentplatform.payment.infrastructure.gateway.toss;

import com.hyoguoo.paymentplatform.payment.application.dto.request.TossCancelGatewayCommand;
import com.hyoguoo.paymentplatform.payment.application.dto.request.TossConfirmGatewayCommand;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentFailureInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentGatewayInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentCancelResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.infrastructure.PaymentInfrastructureMapper;
import com.hyoguoo.paymentplatform.payment.infrastructure.gateway.PaymentGatewayStrategy;
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
    private static final String STATUS_CANCELED = "CANCELED";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_PENDING = "PENDING";
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

        PaymentGatewayInfo paymentGatewayInfo = PaymentInfrastructureMapper.toPaymentGatewayInfo(
                paymentGatewayInternalReceiver.confirmPayment(
                        PaymentInfrastructureMapper.toTossConfirmRequest(tossCommand)
                )
        );

        return convertToPaymentConfirmResult(paymentGatewayInfo, request);
    }

    @Override
    public PaymentCancelResult cancel(PaymentCancelRequest request) {
        TossCancelGatewayCommand tossCommand = TossCancelGatewayCommand.builder()
                .paymentKey(request.paymentKey())
                .cancelReason(request.cancelReason())
                .idempotencyKey(generateIdempotencyKey(request.paymentKey()))
                .build();

        PaymentGatewayInfo paymentGatewayInfo = PaymentInfrastructureMapper.toPaymentGatewayInfo(
                paymentGatewayInternalReceiver.cancelPayment(
                        PaymentInfrastructureMapper.toTossCancelRequest(tossCommand)
                )
        );

        return convertToPaymentCancelResult(paymentGatewayInfo, request);
    }

    private PaymentConfirmResult convertToPaymentConfirmResult(
            PaymentGatewayInfo paymentGatewayInfo,
            PaymentConfirmRequest request
    ) {
        PaymentFailureInfo failure = createPaymentFailureInfo(paymentGatewayInfo);
        PaymentConfirmResultStatus status = determineConfirmResultStatus(paymentGatewayInfo, failure);

        return new PaymentConfirmResult(
                status,
                paymentGatewayInfo.getPaymentKey(),
                request.orderId(),
                request.amount(),
                paymentGatewayInfo.getPaymentDetails() != null
                        ? paymentGatewayInfo.getPaymentDetails().getApprovedAt()
                        : null,
                failure
        );
    }

    private PaymentFailureInfo createPaymentFailureInfo(PaymentGatewayInfo paymentGatewayInfo) {
        if (paymentGatewayInfo.getPaymentFailure() == null) {
            return null;
        }
        return new PaymentFailureInfo(
                paymentGatewayInfo.getPaymentFailure().getCode(),
                paymentGatewayInfo.getPaymentFailure().getMessage(),
                isRetryable(paymentGatewayInfo.getPaymentFailure().getCode())
        );
    }

    private PaymentConfirmResultStatus determineConfirmResultStatus(
            PaymentGatewayInfo paymentGatewayInfo,
            PaymentFailureInfo failure
    ) {
        String tossStatus = paymentGatewayInfo.getPaymentConfirmResultStatus().name();

        if (STATUS_SUCCESS.equals(tossStatus) || STATUS_DONE.equals(tossStatus)) {
            return PaymentConfirmResultStatus.SUCCESS;
        }

        if (failure != null && failure.isRetryable()) {
            return PaymentConfirmResultStatus.RETRYABLE_FAILURE;
        }

        return PaymentConfirmResultStatus.NON_RETRYABLE_FAILURE;
    }

    private PaymentCancelResult convertToPaymentCancelResult(
            PaymentGatewayInfo paymentGatewayInfo,
            PaymentCancelRequest request
    ) {
        PaymentCancelResultStatus status = mapToPaymentCancelResultStatus(
                paymentGatewayInfo.getPaymentConfirmResultStatus().name()
        );

        PaymentFailureInfo failure = createPaymentFailureInfo(paymentGatewayInfo);

        return new PaymentCancelResult(
                status,
                paymentGatewayInfo.getPaymentKey(),
                paymentGatewayInfo.getPaymentDetails() != null
                        ? paymentGatewayInfo.getPaymentDetails().getApprovedAt()
                        : null,
                request.amount(),
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
        return baseKey;
    }
}
