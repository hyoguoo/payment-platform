package com.hyoguoo.paymentplatform.pg.infrastructure.gateway.toss;

import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Toss Payments 에러 코드 분류.
 * pg-service 내부에서만 사용 — payment-service 에는 노출하지 않는다.
 *
 * <p>ALREADY_PROCESSED_PAYMENT 는 {@link TossPaymentGatewayStrategy}에서 DuplicateApprovalHandler
 * 로 분기되므로 {@link #isRetryableError()} / {@link #isFailure()} 양쪽에 포함하지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum TossPaymentErrorCode {

    ALREADY_PROCESSED_PAYMENT(400, "이미 처리된 결제 입니다."),
    PROVIDER_ERROR(400, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),
    INVALID_REQUEST(400, "잘못된 요청입니다."),
    INVALID_API_KEY(400, "잘못된 시크릿키 연동 정보 입니다."),
    INVALID_REJECT_CARD(400, "카드 사용이 거절되었습니다."),
    BELOW_MINIMUM_AMOUNT(400, "최소 결제 금액 미만."),
    INVALID_CARD_EXPIRATION(400, "카드 유효기간 오류."),
    INVALID_STOPPED_CARD(400, "정지된 카드."),
    EXCEED_MAX_DAILY_PAYMENT_COUNT(400, "하루 결제 가능 횟수 초과."),
    INVALID_CARD_NUMBER(400, "카드번호 오류."),
    UNAPPROVED_ORDER_ID(400, "미승인 주문번호."),
    UNAUTHORIZED_KEY(401, "인증되지 않은 키."),
    REJECT_ACCOUNT_PAYMENT(403, "잔액부족."),
    REJECT_CARD_PAYMENT(403, "한도초과/잔액부족."),
    REJECT_CARD_COMPANY(403, "승인 거절."),
    FORBIDDEN_REQUEST(403, "허용되지 않은 요청."),
    EXCEED_MAX_AUTH_COUNT(403, "최대 인증 횟수 초과."),
    EXCEED_MAX_ONE_DAY_AMOUNT(403, "일일 한도 초과."),
    NOT_AVAILABLE_BANK(403, "은행 서비스 시간 외."),
    INVALID_PASSWORD(403, "결제 비밀번호 불일치."),
    FDS_ERROR(403, "위험거래 감지."),
    NOT_FOUND_PAYMENT(404, "존재하지 않는 결제."),
    NOT_FOUND_PAYMENT_SESSION(404, "결제 세션 만료."),
    FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING(500, "내부 처리 실패."),
    FAILED_INTERNAL_SYSTEM_PROCESSING(500, "내부 시스템 처리 실패."),
    UNKNOWN_PAYMENT_ERROR(500, "결제 실패 — 반복 시 카드사 문의."),
    UNKNOWN(500, "알 수 없는 에러."),
    NETWORK_ERROR(500, "네트워크 오류.");

    private final int httpStatusCode;
    private final String description;

    public static TossPaymentErrorCode of(String errorCode) {
        if (errorCode == null) {
            return UNKNOWN;
        }
        return Arrays.stream(values())
                .filter(e -> e.name().equals(errorCode))
                .findFirst()
                .orElse(UNKNOWN);
    }

    /**
     * 재시도 가능 에러 — {@link PgGatewayRetryableException}으로 분류한다.
     */
    public boolean isRetryableError() {
        return switch (this) {
            case PROVIDER_ERROR, FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING,
                 FAILED_INTERNAL_SYSTEM_PROCESSING, UNKNOWN_PAYMENT_ERROR, UNKNOWN, NETWORK_ERROR -> true;
            default -> false;
        };
    }

    public boolean isAlreadyProcessed() {
        return this == ALREADY_PROCESSED_PAYMENT;
    }
}
