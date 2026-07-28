package com.hyoguoo.paymentplatform.payment.presentation.port;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgAttemptHistoryLookupResult;

public interface PgAttemptHistoryViewService {

    /**
     * 주문번호 기준 pg-service 확정 시도 이력을 조회한다.
     *
     * <p>조회 실패(도메인 예외·타임아웃 등 pg-service 호출이 던질 수 있는 어떤 런타임 예외든)는
     * 구현체가 흡수하고 {@link PgAttemptHistoryLookupResult#unavailable()} 로 반환한다 — 예외를
     * 밖으로 던지지 않는다. 밖으로 던지면 상세 화면 전체가 500 으로 깨져 격리 종결·DLQ 재주입
     * 버튼까지 함께 사라진다.
     *
     * @param orderId 조회 대상 주문 ID
     * @return 조회 결과(이력 있음 / 이력 없음 / 조회 불가)
     */
    PgAttemptHistoryLookupResult getAttemptHistory(String orderId);
}
