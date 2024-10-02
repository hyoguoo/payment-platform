package com.hyoguoo.paymentplatform.paymentgateway.exception.common;

import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TossPaymentErrorCode {

    ALREADY_PROCESSED_PAYMENT(400, "이미 처리된 결제 입니다."),
    PROVIDER_ERROR(400, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),
    EXCEED_MAX_CARD_INSTALLMENT_PLAN(400, "설정 가능한 최대 할부 개월 수를 초과했습니다."),
    INVALID_REQUEST(400, "잘못된 요청입니다."),
    NOT_ALLOWED_POINT_USE(400, "포인트 사용이 불가한 카드로 카드 포인트 결제에 실패했습니다."),
    INVALID_API_KEY(400, "잘못된 시크릿키 연동 정보 입니다."),
    INVALID_REJECT_CARD(400, "카드 사용이 거절되었습니다. 카드사 문의가 필요합니다."),
    BELOW_MINIMUM_AMOUNT(400, "신용카드는 결제금액이 100원 이상, 계좌는 200원이상부터 결제가 가능합니다."),
    INVALID_CARD_EXPIRATION(400, "카드 정보를 다시 확인해주세요. (유효기간)"),
    INVALID_STOPPED_CARD(400, "정지된 카드 입니다."),
    EXCEED_MAX_DAILY_PAYMENT_COUNT(400, "하루 결제 가능 횟수를 초과했습니다."),
    NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT(400, "할부가 지원되지 않는 카드 또는 가맹점 입니다."),
    INVALID_CARD_INSTALLMENT_PLAN(400, "할부 개월 정보가 잘못되었습니다."),
    NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN(400, "할부가 지원되지 않는 카드입니다."),
    EXCEED_MAX_PAYMENT_AMOUNT(400, "하루 결제 가능 금액을 초과했습니다."),
    NOT_FOUND_TERMINAL_ID(400, "단말기번호(Terminal Id)가 없습니다. 토스페이먼츠로 문의 바랍니다."),
    INVALID_AUTHORIZE_AUTH(400, "유효하지 않은 인증 방식입니다."),
    INVALID_CARD_LOST_OR_STOLEN(400, "분실 혹은 도난 카드입니다."),
    RESTRICTED_TRANSFER_ACCOUNT(400, "계좌는 등록 후 12시간 뒤부터 결제할 수 있습니다. 관련 정책은 해당 은행으로 문의해주세요."),
    INVALID_CARD_NUMBER(400, "카드번호를 다시 확인해주세요."),
    INVALID_UNREGISTERED_SUBMALL(400, "등록되지 않은 서브몰입니다. 서브몰이 없는 가맹점이라면 안심클릭이나 ISP 결제가 필요합니다."),
    NOT_REGISTERED_BUSINESS(400, "등록되지 않은 사업자 번호입니다."),
    EXCEED_MAX_ONE_DAY_WITHDRAW_AMOUNT(400, "1일 출금 한도를 초과했습니다."),
    EXCEED_MAX_ONE_TIME_WITHDRAW_AMOUNT(400, "1회 출금 한도를 초과했습니다."),
    CARD_PROCESSING_ERROR(400, "카드사에서 오류가 발생했습니다."),
    EXCEED_MAX_AMOUNT(400, "거래금액 한도를 초과했습니다."),
    INVALID_ACCOUNT_INFO_RE_REGISTER(400, "유효하지 않은 계좌입니다. 계좌 재등록 후 시도해주세요."),
    NOT_AVAILABLE_PAYMENT(400, "결제가 불가능한 시간대입니다."),
    UNAPPROVED_ORDER_ID(400, "아직 승인되지 않은 주문번호입니다."),
    UNAUTHORIZED_KEY(401, "인증되지 않은 시크릿 키 혹은 클라이언트 키 입니다."),
    REJECT_ACCOUNT_PAYMENT(403, "잔액부족으로 결제에 실패했습니다."),
    REJECT_CARD_PAYMENT(403, "한도초과 혹은 잔액부족으로 결제에 실패했습니다."),
    REJECT_CARD_COMPANY(403, "결제 승인이 거절되었습니다."),
    FORBIDDEN_REQUEST(403, "허용되지 않은 요청입니다."),
    REJECT_TOSSPAY_INVALID_ACCOUNT(403, "선택하신 출금 계좌가 출금이체 등록이 되어 있지 않아요. 계좌를 다시 등록해 주세요."),
    EXCEED_MAX_AUTH_COUNT(403, "최대 인증 횟수를 초과했습니다. 카드사로 문의해주세요."),
    EXCEED_MAX_ONE_DAY_AMOUNT(403, "일일 한도를 초과했습니다."),
    NOT_AVAILABLE_BANK(403, "은행 서비스 시간이 아닙니다."),
    INVALID_PASSWORD(403, "결제 비밀번호가 일치하지 않습니다."),
    INCORRECT_BASIC_AUTH_FORMAT(403, "잘못된 요청입니다. ':' 를 포함해 인코딩해주세요."),
    FDS_ERROR(403, "[토스페이먼츠] 위험거래가 감지되어 결제가 제한됩니다. 발송된 문자에 포함된 링크를 통해 본인인증 후 결제가 가능합니다."),
    NOT_FOUND_PAYMENT(404, "존재하지 않는 결제 정보 입니다."),
    NOT_FOUND_PAYMENT_SESSION(404, "결제 시간이 만료되어 결제 진행 데이터가 존재하지 않습니다."),
    FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING(500, "결제가 완료되지 않았어요. 다시 시도해주세요."),
    FAILED_INTERNAL_SYSTEM_PROCESSING(500, "내부 시스템 처리 작업이 실패했습니다. 잠시 후 다시 시도해주세요."),
    UNKNOWN_PAYMENT_ERROR(500, "결제에 실패했어요. 같은 문제가 반복된다면 은행이나 카드사로 문의해주세요."),
    UNKNOWN(500, "알 수 없는 에러입니다."),
    NETWORK_ERROR(500, "네트워크 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");

    private final int httpStatusCode;
    private final String description;

    public static TossPaymentErrorCode of(String errorCode) {
        return Arrays.stream(values())
                .filter(e -> e.name().equals(errorCode))
                .findFirst()
                .orElse(UNKNOWN);
    }

    public boolean isSuccess() {
        return this == ALREADY_PROCESSED_PAYMENT;
    }

    public boolean isRetryableError() {
        return switch (this) {
            case PROVIDER_ERROR, FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING,
                 FAILED_INTERNAL_SYSTEM_PROCESSING, UNKNOWN_PAYMENT_ERROR, UNKNOWN, NETWORK_ERROR -> true;
            default -> false;
        };
    }

    public boolean isFailure() {
        return !isSuccess() && !isRetryableError();
    }
}
