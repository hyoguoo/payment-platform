package com.hyoguoo.paymentplatform.pg.application.service;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;

/**
 * 벤더 HTTP 호출 결과 캡슐화 — sealed interface + record 패턴.
 *
 * <p>invokeVendor 가 반환하고 applyOutcome 이 소비한다.
 * try 블록 외부 변수 재할당 금지 패턴 대응.
 *
 * <p>패키지 기본 접근 수준 (package-private) — 동일 패키지 내 PgVendorCallService + 테스트에서만 사용.
 */
sealed interface GatewayOutcome
        permits GatewayOutcome.Success, GatewayOutcome.Retryable,
                GatewayOutcome.NonRetryable, GatewayOutcome.HandledInternally {

    record Success(PgConfirmResult result) implements GatewayOutcome {}

    record Retryable(String message) implements GatewayOutcome {}

    record NonRetryable(String message) implements GatewayOutcome {}

    record HandledInternally(String message) implements GatewayOutcome {}
}
