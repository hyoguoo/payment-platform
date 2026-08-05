package com.hyoguoo.paymentplatform.payment.application.dto.admin;

/**
 * pg-service 벤더 상태 조회 판정 — 승인 / 실패 / 확인 불가 세 값.
 * pg-service {@code PgVendorStatusJudgement} 와 값이 같다 — HTTP 응답의 {@code judgement} 문자열을
 * 그대로 매핑한다.
 */
public enum PgVendorStatusJudgement {
    APPROVED,
    FAILED,
    UNKNOWN
}
