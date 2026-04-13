package com.hyoguoo.paymentplatform.payment.infrastructure.gateway.nicepay;

import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentFailureInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentCancelResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentStatus;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.common.PaymentErrorCode;
import com.hyoguoo.paymentplatform.payment.infrastructure.gateway.PaymentGatewayStrategy;
import com.hyoguoo.paymentplatform.paymentgateway.exception.PaymentGatewayApiException;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.NicepayGatewayInternalReceiver;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.NicepayCancelRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.request.NicepayConfirmRequest;
import com.hyoguoo.paymentplatform.paymentgateway.presentation.dto.response.NicepayPaymentResponse;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
@RequiredArgsConstructor
public class NicepayPaymentGatewayStrategy implements PaymentGatewayStrategy {

    private static final String NICEPAY_RESULT_CODE_SUCCESS = "0000";

    private static final String NICEPAY_STATUS_PAID = "paid";
    private static final String NICEPAY_STATUS_READY = "ready";
    private static final String NICEPAY_STATUS_FAILED = "failed";
    private static final String NICEPAY_STATUS_CANCELLED = "cancelled";
    private static final String NICEPAY_STATUS_PARTIAL_CANCELLED = "partialCancelled";
    private static final String NICEPAY_STATUS_EXPIRED = "expired";

    private final NicepayGatewayInternalReceiver nicepayGatewayInternalReceiver;

    @Override
    public boolean supports(PaymentGatewayType type) {
        return type == PaymentGatewayType.NICEPAY;
    }

    @Override
    public PaymentConfirmResult confirm(PaymentConfirmRequest request) {
        NicepayConfirmRequest confirmRequest = NicepayConfirmRequest.builder()
                .tid(request.paymentKey())
                .amount(request.amount())
                .build();

        return executeConfirmPayment(confirmRequest, request);
    }

    private PaymentConfirmResult executeConfirmPayment(
            NicepayConfirmRequest confirmRequest,
            PaymentConfirmRequest request
    ) {
        try {
            NicepayPaymentResponse response = nicepayGatewayInternalReceiver.confirmPayment(confirmRequest);
            return convertToPaymentConfirmResult(response, request);
        } catch (PaymentGatewayApiException e) {
            throw new IllegalStateException("NicePay 승인 API 호출 실패: " + e.getMessage(), e);
        }
    }

    @Override
    public PaymentCancelResult cancel(PaymentCancelRequest request) {
        NicepayCancelRequest cancelRequest = NicepayCancelRequest.builder()
                .tid(request.paymentKey())
                .reason(request.cancelReason())
                .build();

        NicepayPaymentResponse response = nicepayGatewayInternalReceiver.cancelPayment(cancelRequest);

        return convertToPaymentCancelResult(response, request);
    }

    // 현재 미사용 — 향후 정산/대사(reconciliation) 용도로 예약
    @Override
    public PaymentStatusResult getStatus(String paymentKey, PaymentGatewayType gatewayType) {
        NicepayPaymentResponse response = nicepayGatewayInternalReceiver.getPaymentInfoByTid(paymentKey);
        return convertToPaymentStatusResult(response);
    }

    // 복구 사이클(OutboxProcessingService)의 getStatus 선행 조회 경로에서 사용
    @Override
    public PaymentStatusResult getStatusByOrderId(String orderId, PaymentGatewayType gatewayType)
            throws PaymentGatewayRetryableException, PaymentGatewayNonRetryableException {
        try {
            NicepayPaymentResponse response = nicepayGatewayInternalReceiver.getPaymentInfoByOrderId(orderId);
            return convertToPaymentStatusResult(response);
        } catch (WebClientResponseException e) {
            return handleGetStatusResponseException(e);
        } catch (WebClientRequestException e) {
            throw PaymentGatewayRetryableException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR);
        }
    }

    private PaymentConfirmResult convertToPaymentConfirmResult(
            NicepayPaymentResponse response,
            PaymentConfirmRequest request
    ) {
        PaymentConfirmResultStatus status = determineConfirmResultStatus(response);
        LocalDateTime approvedAt = parseApprovedAt(response.getPaidAt());

        return new PaymentConfirmResult(
                status,
                response.getTid(),
                request.orderId(),
                request.amount(),
                approvedAt,
                null
        );
    }

    private PaymentConfirmResultStatus determineConfirmResultStatus(NicepayPaymentResponse response) {
        if (NICEPAY_RESULT_CODE_SUCCESS.equals(response.getResultCode())) {
            return PaymentConfirmResultStatus.SUCCESS;
        }
        return PaymentConfirmResultStatus.NON_RETRYABLE_FAILURE;
    }

    private PaymentCancelResult convertToPaymentCancelResult(
            NicepayPaymentResponse response,
            PaymentCancelRequest request
    ) {
        PaymentCancelResultStatus status = mapToPaymentCancelResultStatus(response.getResultCode());
        LocalDateTime canceledAt = parseApprovedAt(response.getPaidAt());

        return new PaymentCancelResult(
                status,
                response.getTid(),
                canceledAt,
                request.amount(),
                null
        );
    }

    private PaymentCancelResultStatus mapToPaymentCancelResultStatus(String resultCode) {
        if (NICEPAY_RESULT_CODE_SUCCESS.equals(resultCode)) {
            return PaymentCancelResultStatus.SUCCESS;
        }
        return PaymentCancelResultStatus.FAILURE;
    }

    private PaymentStatusResult convertToPaymentStatusResult(NicepayPaymentResponse response) {
        PaymentStatus status = mapToPaymentStatus(response.getStatus());
        LocalDateTime approvedAt = parseApprovedAt(response.getPaidAt());

        return new PaymentStatusResult(
                response.getTid(),
                response.getOrderId(),
                status,
                response.getAmount(),
                approvedAt,
                null
        );
    }

    private PaymentStatus mapToPaymentStatus(String nicepayStatus) {
        if (nicepayStatus == null) {
            return PaymentStatus.ABORTED;
        }
        return switch (nicepayStatus) {
            case NICEPAY_STATUS_PAID -> PaymentStatus.DONE;
            case NICEPAY_STATUS_READY -> PaymentStatus.READY;
            case NICEPAY_STATUS_FAILED -> PaymentStatus.ABORTED;
            case NICEPAY_STATUS_CANCELLED -> PaymentStatus.CANCELED;
            case NICEPAY_STATUS_PARTIAL_CANCELLED -> PaymentStatus.PARTIAL_CANCELED;
            case NICEPAY_STATUS_EXPIRED -> PaymentStatus.EXPIRED;
            default -> PaymentStatus.of(nicepayStatus);
        };
    }

    private LocalDateTime parseApprovedAt(String paidAt) {
        if (paidAt == null || paidAt.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(paidAt, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"))
                    .toLocalDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private PaymentStatusResult handleGetStatusResponseException(WebClientResponseException e)
            throws PaymentGatewayNonRetryableException, PaymentGatewayRetryableException {
        if (e.getStatusCode().is5xxServerError()) {
            throw PaymentGatewayRetryableException.of(PaymentErrorCode.TOSS_RETRYABLE_ERROR);
        }
        throw PaymentGatewayNonRetryableException.of(PaymentErrorCode.TOSS_NON_RETRYABLE_ERROR);
    }
}
