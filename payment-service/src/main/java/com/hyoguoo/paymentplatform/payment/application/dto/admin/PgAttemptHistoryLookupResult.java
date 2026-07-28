package com.hyoguoo.paymentplatform.payment.application.dto.admin;

import lombok.Builder;
import lombok.Getter;

/**
 * pg-service 확정 시도 이력 조회 결과 — 조회 성공(이력 있음/없음 포함)과 조회 불가(pg-service
 * 장애·타임아웃 등 어떤 런타임 예외든)를 구분해 표현한다.
 *
 * <p>조회 실패 흡수와 폴백 판단은 이 결과를 만드는 {@code PgAttemptHistoryViewServiceImpl}
 * (application 계층, presentation 입력 포트 {@code PgAttemptHistoryViewService} 구현)의
 * 책임이다 — presentation 계층({@code PaymentAdminController})은 이 결과를 모델에 담는
 * 일만 한다.
 */
@Getter
@Builder
public class PgAttemptHistoryLookupResult {

    /** 조회 불가({@code unavailable=true})면 null. */
    private final PgAttemptHistoryInfo history;
    private final boolean unavailable;

    public static PgAttemptHistoryLookupResult available(PgAttemptHistoryInfo history) {
        return PgAttemptHistoryLookupResult.builder()
                .history(history)
                .unavailable(false)
                .build();
    }

    public static PgAttemptHistoryLookupResult unavailable() {
        return PgAttemptHistoryLookupResult.builder()
                .history(null)
                .unavailable(true)
                .build();
    }
}
