package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgFailureInfo;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.event.DuplicateApprovalDetectedEvent;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgConfirmPort;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgStatusLookupPort;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ApplicationEventPublisher;
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
 *   <li>ALREADY_PROCESSED_PAYMENT → {@link DuplicateApprovalDetectedEvent} 발행 후
 *       {@link PgGatewayDuplicateHandledException} 전파.</li>
 *   <li>{@link TossPaymentErrorCode#isRetryableError()} → {@link PgGatewayRetryableException}.</li>
 *   <li>그 외 → {@link PgGatewayNonRetryableException}.</li>
 * </ul>
 *
 * <p>cycle 회피: 이 전략은 PgStatusLookupPort 구현이고 DuplicateApprovalHandler 는 그 포트를 의존하므로,
 * 전략이 핸들러를 직접 호출하면 cycle 이 만들어진다. 직접 호출 대신 ApplicationEventPublisher 로 이벤트를 발행해
 * 핸들러가 EventListener 로 수신하도록 한다.
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

    private final HttpOperator httpOperator;
    private final EncodeUtils encodeUtils;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * DuplicateApprovalHandler 직접 의존 대신 ApplicationEventPublisher 를 주입한다 — cycle 회피.
     * @Value 필드는 생성자 방식으로 주입할 수 없어(Spring SpEL 한계) 필드 방식을 유지한다.
     */
    public TossPaymentGatewayStrategy(
            HttpOperator httpOperator,
            EncodeUtils encodeUtils,
            ApplicationEventPublisher applicationEventPublisher,
            ObjectMapper objectMapper
    ) {
        this.httpOperator = httpOperator;
        this.encodeUtils = encodeUtils;
        this.applicationEventPublisher = applicationEventPublisher;
        this.objectMapper = objectMapper;
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
                    () -> "orderId=" + request.orderId() + " — ALREADY_PROCESSED_PAYMENT DuplicateApprovalDetectedEvent 발행");
            // DuplicateApprovalHandler 직접 호출 대신 ApplicationEvent 를 발행해 cycle 을 끊는다.
            // DuplicateApprovalHandler.onDuplicateApprovalDetected(@EventListener) 가 수신해 처리하며,
            // vendorType 은 PgStatusLookupStrategySelector 로 올바른 전략을 선택할 때 사용된다.
            applicationEventPublisher.publishEvent(new DuplicateApprovalDetectedEvent(
                    request.orderId(), request.amount(), request.paymentKey(),
                    "ALREADY_PROCESSED_PAYMENT", PgVendorType.TOSS));
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
