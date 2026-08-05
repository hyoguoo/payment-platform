package com.hyoguoo.paymentplatform.pg.application.dto;

/**
 * 벤더 상태 조회 판정 — 승인 / 실패 / 확인 불가 세 값.
 * 벤더가 돌려준 세부 상태({@link PgStatusResult#status}, {@code PgPaymentStatus})를
 * 관리자가 종결 여부를 판단하기 쉬운 세 갈래로 접는다.
 */
public enum PgVendorStatusJudgement {
    APPROVED,
    FAILED,
    UNKNOWN
}
