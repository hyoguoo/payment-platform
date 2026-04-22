package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgFailureInfo;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgGatewayPort;
import com.hyoguoo.paymentplatform.pg.application.service.DuplicateApprovalHandler;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgConfirmResultStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Toss Payments PG 벤더 전략 구현체.
 * ADR-21: pg-service 내부 벤더 전략. payment-service 의존 없음.
 *
 * <p>TODO(T2b-01): 실제 HTTP 클라이언트(HttpTossOperator) 주입 + 벤더 호출 구현.
 * ALREADY_PROCESSED_PAYMENT 분기: T2b-05 구현 완료 — DuplicateApprovalHandler 위임.
 *
 * <p>벤더 선택: {@code pg.gateway.type=toss} 속성에서 활성화. 미설정 시 기본값 toss로 동작.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pg.gateway.type", havingValue = "toss", matchIfMissing = true)
public class TossPaymentGatewayStrategy implements PgGatewayPort {

    /**
     * T2b-05: Toss ALREADY_PROCESSED_PAYMENT(중복 승인) 분기에서 DuplicateApprovalHandler 위임.
     * ADR-21(대칭성): NicePay 2201과 동일 경로 재사용.
     * infrastructure → application 방향(허용됨).
     */
    private final DuplicateApprovalHandler duplicateApprovalHandler;

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

    @Override
    public boolean supports(PgVendorType vendorType) {
        return vendorType == PgVendorType.TOSS;
    }

    /**
     * Toss Payments 승인 API 호출.
     * TODO(T2b-01): HttpTossOperator를 주입하여 실제 HTTP 호출 구현.
     *
     * <p>ALREADY_PROCESSED_PAYMENT 응답 시 DuplicateApprovalHandler 위임 분기 자리:
     * [분기 자리] errorCode == "ALREADY_PROCESSED_PAYMENT"
     *   → duplicateApprovalHandler.handleDuplicateApproval(request.orderId(), request.amount(), eventUuid)
     */
    @Override
    public PgConfirmResult confirm(PgConfirmRequest request)
            throws PgGatewayRetryableException, PgGatewayNonRetryableException {
        // TODO(T2b-01): 실제 HTTP 호출 후 errorCode 파싱
        // if ("ALREADY_PROCESSED_PAYMENT".equals(errorCode)) {
        //     duplicateApprovalHandler.handleDuplicateApproval(request.orderId(), request.amount(), eventUuid);
        //     return; // 또는 적절한 반환값
        // }
        throw new UnsupportedOperationException("T2b-01에서 구현 예정");
    }

    /**
     * Toss Payments orderId 기반 상태 조회 API 호출.
     * TODO(T2b-01): HttpTossOperator를 주입하여 실제 HTTP 호출 구현.
     */
    @Override
    public PgStatusResult getStatusByOrderId(String orderId)
            throws PgGatewayRetryableException, PgGatewayNonRetryableException {
        throw new UnsupportedOperationException("T2b-01에서 구현 예정");
    }

    private boolean isRetryable(String errorCode) {
        if (errorCode == null) {
            return false;
        }

        return errorCode.equals(ERROR_PROVIDER_ERROR)
                || errorCode.equals(ERROR_FAILED_PAYMENT_INTERNAL)
                || errorCode.equals(ERROR_FAILED_INTERNAL)
                || errorCode.equals(ERROR_UNKNOWN_PAYMENT)
                || errorCode.equals(STATUS_UNKNOWN)
                || errorCode.equals(ERROR_NETWORK)
                || errorCode.startsWith(ERROR_TIMEOUT_PREFIX)
                || errorCode.equals(ERROR_PAY_PROCESS_ABORTED);
    }

    private PgConfirmResult buildConfirmResult(
            PgConfirmResultStatus status,
            String paymentKey,
            String orderId,
            java.math.BigDecimal amount,
            java.time.LocalDateTime approvedAt,
            PgFailureInfo failure
    ) {
        return new PgConfirmResult(status, paymentKey, orderId, amount, approvedAt, failure);
    }

    private PgStatusResult buildStatusResult(
            String paymentKey,
            String orderId,
            String tossStatus,
            java.math.BigDecimal amount,
            java.time.LocalDateTime approvedAt,
            PgFailureInfo failure
    ) {
        PgPaymentStatus pgStatus = mapToPaymentStatus(tossStatus);
        return new PgStatusResult(paymentKey, orderId, pgStatus, amount, approvedAt, failure);
    }

    private PgPaymentStatus mapToPaymentStatus(String tossStatus) {
        return switch (tossStatus) {
            case STATUS_DONE, STATUS_SUCCESS -> PgPaymentStatus.DONE;
            case STATUS_FAILED, STATUS_FAILURE, STATUS_ABORTED -> PgPaymentStatus.ABORTED;
            case STATUS_IN_PROGRESS, STATUS_PENDING -> PgPaymentStatus.IN_PROGRESS;
            case STATUS_CANCELED -> PgPaymentStatus.CANCELED;
            case STATUS_EXPIRED -> PgPaymentStatus.EXPIRED;
            case STATUS_WAITING_FOR_DEPOSIT -> PgPaymentStatus.WAITING_FOR_DEPOSIT;
            case STATUS_PARTIAL_CANCELED -> PgPaymentStatus.PARTIAL_CANCELED;
            case STATUS_READY -> PgPaymentStatus.READY;
            default -> PgPaymentStatus.ABORTED;
        };
    }
}
