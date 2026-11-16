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
                GatewayOutcome.NonRetryable, GatewayOutcome.HandledInternally,
                GatewayOutcome.ConcurrentCall {

    record Success(PgConfirmResult result) implements GatewayOutcome {}

    record Retryable(String message) implements GatewayOutcome {}

    record NonRetryable(String message) implements GatewayOutcome {}

    /**
     * 벤더가 "이미 처리된 결제"로 답한 경우. 보정에 쓸 벤더 상태 조회는 invokeVendor(TX 밖)에서 미리 해 담는다.
     */
    record HandledInternally(String message, DuplicateApprovalHandler.VendorQueryOutcome vendorQuery)
            implements GatewayOutcome {}

    /**
     * 같은 멱등키로 아직 처리 중인 원 호출과 겹쳐 벤더가 거부한 경우.
     * 원 호출이 결과를 낼 예정이므로 시도횟수·재시도 명령·상태 전이를 건드리지 않는다.
     */
    record ConcurrentCall(String message) implements GatewayOutcome {}
}
