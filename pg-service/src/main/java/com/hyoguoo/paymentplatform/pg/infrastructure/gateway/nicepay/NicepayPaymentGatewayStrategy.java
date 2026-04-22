package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.nicepay;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.application.port.out.PgGatewayPort;
import com.hyoguoo.paymentplatform.pg.application.service.DuplicateApprovalHandler;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgPaymentStatus;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * NicePay PG 벤더 전략 구현체.
 * ADR-21: pg-service 내부 벤더 전략. payment-service 의존 없음.
 *
 * <p>TODO(T2b-01): 실제 HTTP 클라이언트(HttpNicepayOperator) 주입 + 벤더 호출 구현.
 * 중복 승인 응답(2201) 방어: T2b-05 구현 완료 — DuplicateApprovalHandler 위임.
 *
 * <p>벤더 선택: {@code pg.gateway.type=nicepay} 속성에서 활성화.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pg.gateway.type", havingValue = "nicepay")
public class NicepayPaymentGatewayStrategy implements PgGatewayPort {

    private static final String NICEPAY_RESULT_CODE_SUCCESS = "0000";
    private static final String NICEPAY_ERROR_CODE_DUPLICATE_APPROVAL = "2201";

    /**
     * T2b-05: NicePay 2201(중복 승인) 분기에서 DuplicateApprovalHandler 위임.
     * ADR-21(대칭성): Toss ALREADY_PROCESSED_PAYMENT와 동일 경로 재사용.
     * infrastructure → application 방향(허용됨).
     */
    private final DuplicateApprovalHandler duplicateApprovalHandler;

    // 재시도 가능 에러 코드: 일시적 네트워크/서버 오류
    private static final String NICEPAY_RETRYABLE_ERROR_2159 = "2159";
    private static final String NICEPAY_RETRYABLE_ERROR_A246 = "A246";
    private static final String NICEPAY_RETRYABLE_ERROR_A299 = "A299";

    private static final String NICEPAY_STATUS_PAID = "paid";
    private static final String NICEPAY_STATUS_READY = "ready";
    private static final String NICEPAY_STATUS_FAILED = "failed";
    private static final String NICEPAY_STATUS_CANCELLED = "cancelled";
    private static final String NICEPAY_STATUS_PARTIAL_CANCELLED = "partialCancelled";
    private static final String NICEPAY_STATUS_EXPIRED = "expired";

    @Override
    public boolean supports(PgVendorType vendorType) {
        return vendorType == PgVendorType.NICEPAY;
    }

    /**
     * NicePay 승인 API 호출.
     * TODO(T2b-01): HttpNicepayOperator를 주입하여 실제 HTTP 호출 구현.
     *
     * <p>2201(중복 승인) 응답 시: DuplicateApprovalHandler 위임.
     * [분기 자리] errorCode == NICEPAY_ERROR_CODE_DUPLICATE_APPROVAL("2201")
     *   → duplicateApprovalHandler.handleDuplicateApproval(orderId, amount, eventUuid)
     */
    @Override
    public PgConfirmResult confirm(PgConfirmRequest request)
            throws PgGatewayRetryableException, PgGatewayNonRetryableException {
        // TODO(T2b-01): 실제 HTTP 호출 후 errorCode 파싱
        // if (NICEPAY_ERROR_CODE_DUPLICATE_APPROVAL.equals(errorCode)) {
        //     duplicateApprovalHandler.handleDuplicateApproval(request.orderId(), request.amount(), eventUuid);
        //     return; // 또는 적절한 반환값
        // }
        throw new UnsupportedOperationException("T2b-01에서 구현 예정");
    }

    /**
     * NicePay orderId 기반 상태 조회 API 호출.
     * TODO(T2b-01): HttpNicepayOperator를 주입하여 실제 HTTP 호출 구현.
     */
    @Override
    public PgStatusResult getStatusByOrderId(String orderId)
            throws PgGatewayRetryableException, PgGatewayNonRetryableException {
        throw new UnsupportedOperationException("T2b-01에서 구현 예정");
    }

    /**
     * NicePay 2201(중복 승인) 분기 — DuplicateApprovalHandler 위임.
     * T2b-05: NicepayStrategy에서 직접 호출 가능한 진입점. 실제 HTTP 구현 시 confirm() 내부에서 호출.
     *
     * @param orderId       주문 ID
     * @param amount        command payload 금액
     * @param eventUuid     이벤트 UUID
     */
    public void handleDuplicateApproval(String orderId, BigDecimal amount, String eventUuid) {
        log.info("NicepayPaymentGatewayStrategy: 2201 중복 승인 응답 → DuplicateApprovalHandler 위임 orderId={}", orderId);
        duplicateApprovalHandler.handleDuplicateApproval(orderId, amount, eventUuid);
    }

    private boolean isRetryableErrorCode(String errorCode) {
        return NICEPAY_RETRYABLE_ERROR_2159.equals(errorCode)
                || NICEPAY_RETRYABLE_ERROR_A246.equals(errorCode)
                || NICEPAY_RETRYABLE_ERROR_A299.equals(errorCode);
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
            return OffsetDateTime.parse(paidAt, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"))
                    .toLocalDateTime();
        } catch (DateTimeParseException e) {
            log.warn("NicePay paidAt 파싱 실패 — fallback LocalDateTime.now() 사용. paidAt={}", paidAt);
            return LocalDateTime.now();
        }
    }
}
