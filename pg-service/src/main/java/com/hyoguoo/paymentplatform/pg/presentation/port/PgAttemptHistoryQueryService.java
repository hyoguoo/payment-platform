package com.hyoguoo.paymentplatform.pg.presentation.port;

import com.hyoguoo.paymentplatform.pg.application.dto.PgAttemptHistory;

/**
 * pg-service inbound 포트 — presentation → application 계층 계약.
 * 관리자 화면이 결제 확정 시도 이력을 조회할 때 위임하는 조회 전용 포트.
 * 구현체는 application 계층의 {@code PgAttemptHistoryService}.
 */
public interface PgAttemptHistoryQueryService {

    /**
     * 주문번호 기준 확정 시도 이력을 조회한다.
     * 해당 주문이 pg-service 에 없으면 {@link PgAttemptHistory#notFound} 를 반환한다 —
     * 조회 자체의 실패(예외)와 다른 상태다.
     *
     * @param orderId 주문 ID
     * @return 조립된 시도 이력
     */
    PgAttemptHistory getAttemptHistory(String orderId);
}
