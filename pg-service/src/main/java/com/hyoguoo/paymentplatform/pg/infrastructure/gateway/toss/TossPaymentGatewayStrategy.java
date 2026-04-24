package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss;

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
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.dto.TossConfirmCommand;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.dto.TossPaymentApiFailResponse;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.dto.TossPaymentApiResponse;
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
 * Toss Payments PG 벤더 전략 실구현.
 * ADR-21 / ADR-30: pg-service 내부 벤더 호출 — payment-service 의존 없음.
 *
 * <p>승인 API: POST {tossApiUrl}/confirm (Basic 인증 + Idempotency-Key).
 * 조회 API: GET {tossApiUrl}/orders/{orderId}.
 *
 * <p>에러 분기:
 * <ul>
 *   <li>ALREADY_PROCESSED_PAYMENT → {@link DuplicateApprovalHandler} 위임 후
 *       {@link PgGatewayDuplicateHandledException} 전파.</li>
 *   <li>{@link TossPaymentErrorCode#isRetryableError()} → {@link PgGatewayRetryableException}.</li>
 *   <li>그 외 → {@link PgGatewayNonRetryableException}.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pg.gateway.type", havingValue = "toss", matchIfMissing = true)
public class TossPaymentGatewayStrategy implements PgStatusLookupPort, PgConfirmPort {

    private static final String AUTHORIZATION_HEADER_NAME = "Authorization";
    private static final String IDEMPOTENCY_KEY_HEADER_NAME = "Idempotency-Key";
    private static final String BASIC_AUTHORIZATION_TYPE = "Basic ";
    private static final String NETWORK_ERROR_CODE = "NETWORK_ERROR";
    private static final String NETWORK_ERROR_MESSAGE = "네트워크 오류가 발생했습니다. 잠시 후 다시 시도해주세요.";
    private static final String UNAUTHORIZED_CODE = "UNAUTHORIZED_KEY";
    private static final String UNAUTHORIZED_MESSAGE = "인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다.";

    private final HttpOperator httpOperator;
    private final EncodeUtils encodeUtils;
    private final DuplicateApprovalHandler duplicateApprovalHandler;
    private final ObjectMapper objectMapper;

    @Value("${spring.myapp.toss-payments.secret-key}")
    private String secretKey;

    @Value("${spring.myapp.toss-payments.api-url}")
    private String tossApiUrl;

    @Override
    public boolean supports(PgVendorType vendorType) {
        return vendorType == PgVendorType.TOSS;
    }

    @Override
    public PgConfirmResult confirm(PgConfirmRequest request)
            throws PgGatewayRetryableException, PgGatewayNonRetryableException {
        Map<String, String> headers = Map.of(
                AUTHORIZATION_HEADER_NAME, generateBasicAuthHeaderValue(),
                IDEMPOTENCY_KEY_HEADER_NAME, request.orderId()
        );
        TossConfirmCommand body = new TossConfirmCommand(
                request.paymentKey(), request.orderId(), request.amount());

        try {
            TossPaymentApiResponse response = httpOperator.requestPost(
                    tossApiUrl + "/confirm", headers, body, TossPaymentApiResponse.class);
            return toConfirmResult(response);
        } catch (RestClientResponseException e) {
            handleErrorResponse(e, request);
            throw new IllegalStateException("unreachable — handleErrorResponse 는 항상 예외를 던진다");
        } catch (ResourceAccessException e) {
            // I/O 레벨 실패(커넥션 타임아웃·리셋 등) — 재시도 가능
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
            TossPaymentApiResponse response = httpOperator.requestGet(
                    tossApiUrl + "/orders/" + orderId, headers, TossPaymentApiResponse.class);
            return toStatusResult(response);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw PgGatewayNonRetryableException.of(UNAUTHORIZED_MESSAGE);
            }
            TossPaymentErrorCode code = classifyError(e.getResponseBodyAsString());
            if (code.isRetryableError()) {
                throw PgGatewayRetryableException.of(code.name());
            }
            throw PgGatewayNonRetryableException.of(code.name());
        } catch (ResourceAccessException e) {
            throw PgGatewayRetryableException.of(NETWORK_ERROR_MESSAGE);
        }
    }

    // -----------------------------------------------------------------------
    // 내부 구현
    // -----------------------------------------------------------------------

    private void handleErrorResponse(RestClientResponseException e, PgConfirmRequest request) {
        if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
            throw PgGatewayNonRetryableException.of(UNAUTHORIZED_CODE + ": " + UNAUTHORIZED_MESSAGE);
        }
        TossPaymentApiFailResponse fail = parseErrorResponse(e.getResponseBodyAsString());
        TossPaymentErrorCode code = TossPaymentErrorCode.of(fail.code());

        if (code.isAlreadyProcessed()) {
            LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_DUPLICATE_HANDLED,
                    () -> "orderId=" + request.orderId() + " — ALREADY_PROCESSED_PAYMENT DuplicateApprovalHandler 위임");
            duplicateApprovalHandler.handleDuplicateApproval(
                    request.orderId(), request.amount(), request.orderId());
            throw PgGatewayDuplicateHandledException.of(
                    "ALREADY_PROCESSED_PAYMENT handled for orderId=" + request.orderId());
        }

        String detail = fail.code() + ": " + fail.message();
        if (code.isRetryableError()) {
            LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_RETRYABLE_ERROR,
                    () -> "orderId=" + request.orderId() + " detail=" + detail);
            throw PgGatewayRetryableException.of(detail);
        }
        LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_NON_RETRYABLE_ERROR,
                () -> "orderId=" + request.orderId() + " detail=" + detail);
        throw PgGatewayNonRetryableException.of(detail);
    }

    private TossPaymentErrorCode classifyError(String responseBody) {
        TossPaymentApiFailResponse fail = parseErrorResponse(responseBody);
        return TossPaymentErrorCode.of(fail.code());
    }

    private PgConfirmResult toConfirmResult(TossPaymentApiResponse response) {
        PgPaymentStatus pgStatus = TossPaymentStatus.of(response.status())
                .map(TossPaymentStatus::getPgStatus)
                .orElse(PgPaymentStatus.ABORTED);
        PgConfirmResultStatus resultStatus = pgStatus == PgPaymentStatus.DONE
                ? PgConfirmResultStatus.SUCCESS
                : PgConfirmResultStatus.NON_RETRYABLE_FAILURE;
        PgFailureInfo failure = response.failure() != null
                ? new PgFailureInfo(response.failure().code(), response.failure().message(), false)
                : null;
        return new PgConfirmResult(
                resultStatus,
                response.paymentKey(),
                response.orderId(),
                BigDecimal.valueOf(response.totalAmount()),
                parseApprovedAt(response.approvedAt()),
                failure,
                response.approvedAt()   // T-A1: raw ISO-8601 문자열 보존 (ConfirmedEventPayload 직렬화 용)
        );
    }

    private PgStatusResult toStatusResult(TossPaymentApiResponse response) {
        PgPaymentStatus pgStatus = TossPaymentStatus.of(response.status())
                .map(TossPaymentStatus::getPgStatus)
                .orElse(PgPaymentStatus.ABORTED);
        PgFailureInfo failure = response.failure() != null
                ? new PgFailureInfo(response.failure().code(), response.failure().message(), false)
                : null;
        return new PgStatusResult(
                response.paymentKey(),
                response.orderId(),
                pgStatus,
                BigDecimal.valueOf(response.totalAmount()),
                parseApprovedAt(response.approvedAt()),
                failure
        );
    }

    private LocalDateTime parseApprovedAt(String approvedAt) {
        if (approvedAt == null || approvedAt.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(approvedAt, TossPaymentApiResponse.DATE_TIME_FORMATTER)
                    .toLocalDateTime();
        } catch (DateTimeParseException e) {
            LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_PARSE_ERROR,
                    () -> "approvedAt 파싱 실패 fallback=now approvedAt=" + approvedAt);
            return LocalDateTime.now();
        }
    }

    private String generateBasicAuthHeaderValue() {
        return BASIC_AUTHORIZATION_TYPE + encodeUtils.encodeBase64(secretKey + ":");
    }

    private TossPaymentApiFailResponse parseErrorResponse(String errorResponse) {
        if (errorResponse == null || errorResponse.isBlank()) {
            return new TossPaymentApiFailResponse(NETWORK_ERROR_CODE, NETWORK_ERROR_MESSAGE);
        }
        try {
            return objectMapper.readValue(errorResponse, TossPaymentApiFailResponse.class);
        } catch (JsonProcessingException e) {
            LogFmt.warn(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_PARSE_ERROR,
                    () -> "에러 응답 파싱 실패 — UNKNOWN 처리 raw=" + errorResponse);
            return new TossPaymentApiFailResponse("UNKNOWN", errorResponse);
        }
    }
}
