package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.nicepay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgFailureInfo;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgConfirmPort;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgStatusLookupPort;
import com.hyoguoo.paymentplatform.pg.application.service.DuplicateApprovalHandler;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgConfirmResultStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayDuplicateHandledException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.nicepay.dto.NicepayPaymentApiFailResponse;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.nicepay.dto.NicepayPaymentApiResponse;
import com.hyoguoo.paymentplatform.pg.infrastructure.http.EncodeUtils;
import com.hyoguoo.paymentplatform.pg.infrastructure.http.HttpOperator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * NicePay PG 벤더 전략 실구현.
 * ADR-21 / ADR-30: pg-service 내부 벤더 호출 — payment-service 의존 없음.
 *
 * <p>승인 API: POST {nicepayApiUrl}/v1/payments/{tid} (Basic 인증).
 * 조회 API: GET {nicepayApiUrl}/v1/payments/find/{orderId}.
 *
 * <p>에러 분기:
 * <ul>
 *   <li>2201(중복 승인) → {@link DuplicateApprovalHandler} 위임 후
 *       {@link PgGatewayDuplicateHandledException} 전파.</li>
 *   <li>2159 / A246 / A299 → {@link PgGatewayRetryableException}.</li>
 *   <li>그 외 → {@link PgGatewayNonRetryableException}.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pg.gateway.type", havingValue = "nicepay")
public class NicepayPaymentGatewayStrategy implements PgStatusLookupPort, PgConfirmPort {

    private static final String AUTHORIZATION_HEADER_NAME = "Authorization";
    private static final String BASIC_AUTHORIZATION_TYPE = "Basic ";

    private static final String NICEPAY_RESULT_CODE_SUCCESS = "0000";
    private static final String NICEPAY_ERROR_CODE_DUPLICATE_APPROVAL = "2201";

    private static final String NICEPAY_RETRYABLE_ERROR_2159 = "2159";
    private static final String NICEPAY_RETRYABLE_ERROR_A246 = "A246";
    private static final String NICEPAY_RETRYABLE_ERROR_A299 = "A299";

    private static final String NETWORK_ERROR_CODE = "NETWORK_ERROR";
    private static final String NETWORK_ERROR_MESSAGE = "네트워크 오류가 발생했습니다. 잠시 후 다시 시도해주세요.";
    private static final String UNAUTHORIZED_MESSAGE = "인증되지 않은 NicePay 클라이언트/시크릿 키.";

    private static final String NICEPAY_STATUS_PAID = "paid";
    private static final String NICEPAY_STATUS_READY = "ready";
    private static final String NICEPAY_STATUS_FAILED = "failed";
    private static final String NICEPAY_STATUS_CANCELLED = "cancelled";
    private static final String NICEPAY_STATUS_PARTIAL_CANCELLED = "partialCancelled";
    private static final String NICEPAY_STATUS_EXPIRED = "expired";

    private final HttpOperator httpOperator;
    private final EncodeUtils encodeUtils;
    private final DuplicateApprovalHandler duplicateApprovalHandler;
    private final ObjectMapper objectMapper;

    @Value("${spring.myapp.nicepay.client-key}")
    private String clientKey;

    @Value("${spring.myapp.nicepay.secret-key}")
    private String secretKey;

    @Value("${spring.myapp.nicepay.api-url}")
    private String nicepayApiUrl;

    @Override
    public boolean supports(PgVendorType vendorType) {
        return vendorType == PgVendorType.NICEPAY;
    }

    @Override
    public PgConfirmResult confirm(PgConfirmRequest request)
            throws PgGatewayRetryableException, PgGatewayNonRetryableException {
        Map<String, String> headers = Map.of(
                AUTHORIZATION_HEADER_NAME, generateBasicAuthHeaderValue()
        );
        // NicePay 승인: tid == paymentKey (client-side authnet에서 전달받은 거래 ID)
        String tid = request.paymentKey();
        Map<String, Object> body = Map.of("amount", request.amount());

        try {
            NicepayPaymentApiResponse response = httpOperator.requestPost(
                    nicepayApiUrl + "/v1/payments/" + tid, headers, body,
                    NicepayPaymentApiResponse.class);
            return handleConfirmResponse(response, request);
        } catch (RestClientResponseException e) {
            throw classifyConfirmError(e, request);
        } catch (ResourceAccessException e) {
            LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_NETWORK_ERROR,
                    () -> "orderId=" + request.orderId() + " cause=" + e.getMessage());
            throw PgGatewayRetryableException.of(NETWORK_ERROR_MESSAGE);
        }
    }

    @Override
    public PgStatusResult getStatusByOrderId(String orderId)
            throws PgGatewayRetryableException, PgGatewayNonRetryableException {
        Map<String, String> headers = Map.of(
                AUTHORIZATION_HEADER_NAME, generateBasicAuthHeaderValue()
        );
        try {
            NicepayPaymentApiResponse response = httpOperator.requestGet(
                    nicepayApiUrl + "/v1/payments/find/" + orderId, headers,
                    NicepayPaymentApiResponse.class);
            return toStatusResult(response);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw PgGatewayNonRetryableException.of(UNAUTHORIZED_MESSAGE);
            }
            NicepayPaymentApiFailResponse fail = parseErrorResponse(e.getResponseBodyAsString());
            if (isRetryableErrorCode(fail.resultCode())) {
                throw PgGatewayRetryableException.of(fail.resultCode() + ": " + fail.resultMsg());
            }
            throw PgGatewayNonRetryableException.of(fail.resultCode() + ": " + fail.resultMsg());
        } catch (ResourceAccessException e) {
            throw PgGatewayRetryableException.of(NETWORK_ERROR_MESSAGE);
        }
    }

    // -----------------------------------------------------------------------
    // 내부 구현
    // -----------------------------------------------------------------------

    private PgConfirmResult handleConfirmResponse(NicepayPaymentApiResponse response, PgConfirmRequest request) {
        // NicePay는 2xx 상태 코드로도 resultCode != "0000" 인 실패 응답을 돌려줄 수 있다 — body 필드로 판단.
        if (NICEPAY_RESULT_CODE_SUCCESS.equals(response.resultCode())) {
            return toConfirmResult(response);
        }

        if (NICEPAY_ERROR_CODE_DUPLICATE_APPROVAL.equals(response.resultCode())) {
            LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_DUPLICATE_HANDLED,
                    () -> "orderId=" + request.orderId() + " — 2201 중복 승인 DuplicateApprovalHandler 위임");
            duplicateApprovalHandler.handleDuplicateApproval(
                    request.orderId(), request.amount());
            throw PgGatewayDuplicateHandledException.of(
                    "2201 handled for orderId=" + request.orderId());
        }

        String detail = response.resultCode() + ": " + response.resultMsg();
        if (isRetryableErrorCode(response.resultCode())) {
            LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_RETRYABLE_ERROR,
                    () -> "orderId=" + request.orderId() + " detail=" + detail);
            throw PgGatewayRetryableException.of(detail);
        }
        LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_NON_RETRYABLE_ERROR,
                () -> "orderId=" + request.orderId() + " detail=" + detail);
        throw PgGatewayNonRetryableException.of(detail);
    }

    private RuntimeException classifyConfirmError(RestClientResponseException e, PgConfirmRequest request) {
        if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
            return PgGatewayNonRetryableException.of(UNAUTHORIZED_MESSAGE);
        }
        NicepayPaymentApiFailResponse fail = parseErrorResponse(e.getResponseBodyAsString());

        if (NICEPAY_ERROR_CODE_DUPLICATE_APPROVAL.equals(fail.resultCode())) {
            LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_DUPLICATE_HANDLED,
                    () -> "orderId=" + request.orderId() + " — 2201 중복 승인(HTTP body) DuplicateApprovalHandler 위임");
            duplicateApprovalHandler.handleDuplicateApproval(
                    request.orderId(), request.amount());
            return PgGatewayDuplicateHandledException.of(
                    "2201 handled for orderId=" + request.orderId());
        }

        String detail = fail.resultCode() + ": " + fail.resultMsg();
        if (isRetryableErrorCode(fail.resultCode())) {
            return PgGatewayRetryableException.of(detail);
        }
        return PgGatewayNonRetryableException.of(detail);
    }

    private boolean isRetryableErrorCode(String errorCode) {
        return NICEPAY_RETRYABLE_ERROR_2159.equals(errorCode)
                || NICEPAY_RETRYABLE_ERROR_A246.equals(errorCode)
                || NICEPAY_RETRYABLE_ERROR_A299.equals(errorCode);
    }

    private PgConfirmResult toConfirmResult(NicepayPaymentApiResponse response) {
        PgPaymentStatus pgStatus = mapToPaymentStatus(response.status());
        PgConfirmResultStatus resultStatus = pgStatus == PgPaymentStatus.DONE
                ? PgConfirmResultStatus.SUCCESS
                : PgConfirmResultStatus.NON_RETRYABLE_FAILURE;
        return new PgConfirmResult(
                resultStatus,
                response.tid(),
                response.orderId(),
                response.amount(),
                parseApprovedAt(response.paidAt()),
                null,
                response.paidAt()   // T-A1: raw ISO-8601 문자열 보존 (ConfirmedEventPayload 직렬화 용)
        );
    }

    private PgStatusResult toStatusResult(NicepayPaymentApiResponse response) {
        PgPaymentStatus pgStatus = mapToPaymentStatus(response.status());
        return new PgStatusResult(
                response.tid(),
                response.orderId(),
                pgStatus,
                response.amount(),
                parseApprovedAt(response.paidAt()),
                null
        );
    }

    private PgPaymentStatus mapToPaymentStatus(String nicepayStatus) {
        if (nicepayStatus == null) {
            return PgPaymentStatus.ABORTED;
        }
        return switch (nicepayStatus) {
            case NICEPAY_STATUS_PAID -> PgPaymentStatus.DONE;
            case NICEPAY_STATUS_READY -> PgPaymentStatus.READY;
            case NICEPAY_STATUS_FAILED -> PgPaymentStatus.ABORTED;
            case NICEPAY_STATUS_CANCELLED -> PgPaymentStatus.CANCELED;
            case NICEPAY_STATUS_PARTIAL_CANCELLED -> PgPaymentStatus.PARTIAL_CANCELED;
            case NICEPAY_STATUS_EXPIRED -> PgPaymentStatus.EXPIRED;
            default -> PgPaymentStatus.ABORTED;
        };
    }

    private LocalDateTime parseApprovedAt(String paidAt) {
        if (paidAt == null || paidAt.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(paidAt, NicepayPaymentApiResponse.DATE_TIME_FORMATTER)
                    .toLocalDateTime();
        } catch (DateTimeParseException e) {
            LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_PARSE_ERROR,
                    () -> "paidAt 파싱 실패 fallback=now paidAt=" + paidAt);
            return LocalDateTime.now();
        }
    }

    private String generateBasicAuthHeaderValue() {
        return BASIC_AUTHORIZATION_TYPE + encodeUtils.encodeBase64(clientKey + ":" + secretKey);
    }

    private NicepayPaymentApiFailResponse parseErrorResponse(String errorResponse) {
        if (errorResponse == null || errorResponse.isBlank()) {
            return new NicepayPaymentApiFailResponse(NETWORK_ERROR_CODE, NETWORK_ERROR_MESSAGE);
        }
        try {
            return objectMapper.readValue(errorResponse, NicepayPaymentApiFailResponse.class);
        } catch (JsonProcessingException e) {
            LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_PARSE_ERROR,
                    () -> "에러 응답 파싱 실패 raw=" + errorResponse);
            return new NicepayPaymentApiFailResponse("UNKNOWN", errorResponse);
        }
    }
}
