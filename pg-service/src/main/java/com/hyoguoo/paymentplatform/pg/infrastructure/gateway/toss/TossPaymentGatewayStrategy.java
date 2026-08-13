package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgFailureInfo;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgConfirmPort;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgStatusLookupPort;
import com.hyoguoo.paymentplatform.pg.core.common.log.EventType;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogDomain;
import com.hyoguoo.paymentplatform.pg.core.common.log.LogFmt;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgConfirmResultStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayConcurrentCallException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayDuplicateHandledException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.dto.TossConfirmCommand;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.dto.TossPaymentApiFailResponse;
import com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss.dto.TossPaymentApiResponse;
import com.hyoguoo.paymentplatform.pg.infrastructure.http.EncodeUtils;
import com.hyoguoo.paymentplatform.pg.infrastructure.http.HttpOperator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Toss Payments PG 벤더 전략 실구현.
 * pg-service 내부에서만 호출 — payment-service 의존 없음.
 *
 * <p>승인 API: POST {tossApiUrl}/confirm (Basic 인증 + Idempotency-Key).
 * 조회 API: GET {tossApiUrl}/orders/{orderId}.
 *
 * <p>에러 분기:
 * <ul>
 *   <li>ALREADY_PROCESSED_PAYMENT → {@link PgGatewayDuplicateHandledException} 전파.</li>
 *   <li>IDEMPOTENT_REQUEST_PROCESSING(겹친 호출 거부) → {@link PgGatewayConcurrentCallException} 전파.
 *       원 호출이 결과를 낼 예정이라 재시도 대상(UNKNOWN)으로 흡수시키지 않는다.</li>
 *   <li>{@link TossPaymentErrorCode#isRetryableError()} → {@link PgGatewayRetryableException}.</li>
 *   <li>그 외 → {@link PgGatewayNonRetryableException}.</li>
 * </ul>
 *
 * <p>중복 승인 응답은 이 전략이 직접 처리하지 않는다 — 예외만 던지면 벤더 호출 서비스
 * (PgVendorCallService)가 이를 받아 DuplicateApprovalHandler.handleDuplicateApproval 을 호출한다.
 * 그 서비스는 PgStatusLookupPort 를 의존하지 않으므로, 이 전략이 그 포트를 구현하고 있어도
 * 순환 의존은 생기지 않는다.
 *
 * <p>활성화 조건: {@code pg.gateway.type=fake} 가 아닐 때 항상 활성된다 — NicePay 전략과 동시에 등록된다.
 * fake 모드에서는 FakePgGatewayStrategy 가 대신 동작한다.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${pg.gateway.type:vendor}' != 'fake'")
public class TossPaymentGatewayStrategy implements PgStatusLookupPort, PgConfirmPort {

    private static final String AUTHORIZATION_HEADER_NAME = "Authorization";
    private static final String IDEMPOTENCY_KEY_HEADER_NAME = "Idempotency-Key";
    private static final String BASIC_AUTHORIZATION_TYPE = "Basic ";
    private static final String NETWORK_ERROR_CODE = "NETWORK_ERROR";
    private static final String NETWORK_ERROR_MESSAGE = "네트워크 오류가 발생했습니다. 잠시 후 다시 시도해주세요.";
    private static final String UNAUTHORIZED_CODE = "UNAUTHORIZED_KEY";
    private static final String UNAUTHORIZED_MESSAGE = "인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다.";
    private static final int MAX_PARSE_FAILURE_LOG_LENGTH = 500;

    private final HttpOperator httpOperator;
    private final EncodeUtils encodeUtils;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * @Value 필드는 생성자 방식으로 주입할 수 없어(Spring SpEL 한계) 필드 방식을 유지한다.
     * clock 은 parseApprovedAt 파싱 실패 fallback 시 TZ 누수 제거를 위해 주입한다.
     */
    public TossPaymentGatewayStrategy(
            HttpOperator httpOperator,
            EncodeUtils encodeUtils,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.httpOperator = httpOperator;
        this.encodeUtils = encodeUtils;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

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
                    () -> "orderId=" + request.orderId() + " — ALREADY_PROCESSED_PAYMENT 예외 전파");
            throw PgGatewayDuplicateHandledException.of(
                    "ALREADY_PROCESSED_PAYMENT handled for orderId=" + request.orderId());
        }

        if (code.isConcurrentCall()) {
            LogFmt.info(log, LogDomain.PG_VENDOR, EventType.PG_VENDOR_CONCURRENT_CALL,
                    () -> "orderId=" + request.orderId() + " — IDEMPOTENT_REQUEST_PROCESSING 겹침 거부");
            throw PgGatewayConcurrentCallException.of(
                    "IDEMPOTENT_REQUEST_PROCESSING handled for orderId=" + request.orderId());
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
                response.approvedAt()   // raw ISO-8601 문자열 보존 — ConfirmedEventPayload 직렬화 시 그대로 사용
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
                failure,
                response.approvedAt()   // raw ISO-8601 문자열 보존 — toConfirmResult 와 동일하게 그대로 사용
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
                    () -> "approvedAt 파싱 실패 fallback=clock approvedAt=" + approvedAt);
            // TZ 누수 제거 — LocalDateTime.now()는 시스템 TZ 기반이므로 clock.instant() UTC 기준으로 교체.
            // approvedAtRaw(String)는 정상 경로에서만 설정되므로 이 fallback은 ConfirmedEventPayload.approvedAt 에 영향 없음.
            Instant fallback = clock.instant();
            return LocalDateTime.ofInstant(fallback, ZoneOffset.UTC);
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
                    () -> "에러 응답 파싱 실패 — UNKNOWN 처리 raw=" + truncateForLog(errorResponse));
            return new TossPaymentApiFailResponse("UNKNOWN", errorResponse);
        }
    }

    /**
     * 벤더 응답 원문은 예상 못한 외부 입력이라 길이가 임의로 커질 수 있다 — 파싱 실패 로그에서만
     * 상한으로 자르고, 잘렸음을 표시해 원래 짧은 원문과 구분한다. 반환되는 실패 응답(UNKNOWN 처리)의
     * 원문 필드는 이 자르기와 무관하게 그대로 유지된다.
     */
    private static String truncateForLog(String raw) {
        if (raw.length() <= MAX_PARSE_FAILURE_LOG_LENGTH) {
            return raw;
        }
        return raw.substring(0, MAX_PARSE_FAILURE_LOG_LENGTH)
                + "...(truncated, originalLength=" + raw.length() + ")";
    }
}
