package com.hyoguoo.paymentplatform.payment.infrastructure.gateway.nicepay;

import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentFailureInfo;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentCancelResultStatus;
import com.hyoguoo.paymentplatform.payment.domain.dto.enums.PaymentConfirmResultStatus;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NicepayPaymentGatewayStrategy implements PaymentGatewayStrategy {

    private static final String NICEPAY_RESULT_CODE_SUCCESS = "0000";
    private static final String NICEPAY_ERROR_CODE_DUPLICATE_APPROVAL = "2201";

    // 재시도 가능 에러 코드: 일시적 네트워크/서버 오류
    private static final String NICEPAY_RETRYABLE_ERROR_2159 = "2159";
    private static final String NICEPAY_RETRYABLE_ERROR_A246 = "A246";
    private static final String NICEPAY_RETRYABLE_ERROR_A299 = "A299";

    // 재시도 불가 에러 코드: 영구적 오류 (카드 한도/거절/유효기간 등)
    private static final String NICEPAY_NON_RETRYABLE_ERROR_3011 = "3011";
    private static final String NICEPAY_NON_RETRYABLE_ERROR_3012 = "3012";
    private static final String NICEPAY_NON_RETRYABLE_ERROR_3013 = "3013";
    private static final String NICEPAY_NON_RETRYABLE_ERROR_3014 = "3014";
    private static final String NICEPAY_NON_RETRYABLE_ERROR_2152 = "2152";
    private static final String NICEPAY_NON_RETRYABLE_ERROR_2156 = "2156";

    private static final String NICEPAY_STATUS_PAID = "paid";

    private final NicepayGatewayInternalReceiver nicepayGatewayInternalReceiver;

    @Override
    public boolean supports(PaymentGatewayType type) {
        return type == PaymentGatewayType.NICEPAY;
    }

    @Override
    public PaymentConfirmResult confirm(PaymentConfirmRequest request)
            throws PaymentGatewayRetryableException, PaymentGatewayNonRetryableException {
        NicepayConfirmRequest confirmRequest = NicepayConfirmRequest.builder()
                .tid(request.paymentKey())
                .amount(request.amount())
                .build();

        return executeConfirmPayment(confirmRequest, request);
    }

    private PaymentConfirmResult executeConfirmPayment(
            NicepayConfirmRequest confirmRequest,
            PaymentConfirmRequest request
    ) throws PaymentGatewayRetryableException, PaymentGatewayNonRetryableException {
        try {
            NicepayPaymentResponse response = nicepayGatewayInternalReceiver.confirmPayment(confirmRequest);
            return convertToPaymentConfirmResult(response, request);
        } catch (PaymentGatewayApiException e) {
            if (NICEPAY_ERROR_CODE_DUPLICATE_APPROVAL.equals(e.getCode())) {
                return handleDuplicateApprovalCompensation(request);
            }
            return classifyAndThrowConfirmException(e);
        }
    }

    private PaymentConfirmResult classifyAndThrowConfirmException(PaymentGatewayApiException e)
            throws PaymentGatewayRetryableException, PaymentGatewayNonRetryableException {
        if (isRetryableErrorCode(e.getCode())) {
            throw PaymentGatewayRetryableException.of(PaymentErrorCode.GATEWAY_RETRYABLE_ERROR);
        }
        throw PaymentGatewayNonRetryableException.of(PaymentErrorCode.GATEWAY_NON_RETRYABLE_ERROR);
    }

    private PaymentConfirmResult handleDuplicateApprovalCompensation(PaymentConfirmRequest request)
            throws PaymentGatewayRetryableException, PaymentGatewayNonRetryableException {
        try {
            NicepayPaymentResponse statusResponse =
                    nicepayGatewayInternalReceiver.getPaymentInfoByTid(request.paymentKey());
            return resolveCompensationResult(statusResponse, request);
        } catch (PaymentGatewayNonRetryableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw PaymentGatewayRetryableException.of(PaymentErrorCode.GATEWAY_RETRYABLE_ERROR);
        }
    }

    private PaymentConfirmResult resolveCompensationResult(
            NicepayPaymentResponse statusResponse,
            PaymentConfirmRequest request
    ) throws PaymentGatewayNonRetryableException {
        if (NICEPAY_STATUS_PAID.equals(statusResponse.getStatus())) {
            if (request.amount().compareTo(statusResponse.getAmount()) != 0) {
                throw PaymentGatewayNonRetryableException.of(PaymentErrorCode.GATEWAY_NON_RETRYABLE_ERROR);
            }
            LocalDateTime approvedAt = parseApprovedAt(statusResponse.getPaidAt());
            return new PaymentConfirmResult(
                    PaymentConfirmResultStatus.SUCCESS,
                    statusResponse.getTid(),
                    request.orderId(),
                    request.amount(),
                    approvedAt,
                    null
            );
        }
        throw PaymentGatewayNonRetryableException.of(PaymentErrorCode.GATEWAY_NON_RETRYABLE_ERROR);
    }

    @Override
    public PaymentCancelResult cancel(PaymentCancelRequest request) {
        NicepayCancelRequest cancelRequest = NicepayCancelRequest.builder()
                .tid(request.paymentKey())
                .orderId(request.orderId())
                .reason(request.cancelReason())
                .build();

        NicepayPaymentResponse response = nicepayGatewayInternalReceiver.cancelPayment(cancelRequest);

        return convertToPaymentCancelResult(response, request);
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

    private LocalDateTime parseApprovedAt(String paidAt) {
        if (paidAt == null || paidAt.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(paidAt, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"))
                    .toLocalDateTime();
        } catch (DateTimeParseException e) {
            log.warn("NicePay paidAt 파싱 실패 — fallback LocalDateTime.now() 사용. paidAt={}", paidAt);
            return LocalDateTime.now();
        }
    }

    private boolean isRetryableErrorCode(String errorCode) {
        return NICEPAY_RETRYABLE_ERROR_2159.equals(errorCode)
                || NICEPAY_RETRYABLE_ERROR_A246.equals(errorCode)
                || NICEPAY_RETRYABLE_ERROR_A299.equals(errorCode);
    }
}
