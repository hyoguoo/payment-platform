package com.hyoguoo.paymentplatform.pg.presentation.port;

import com.hyoguoo.paymentplatform.pg.application.dto.PgVendorStatusView;

/**
 * pg-service inbound 포트 — presentation → application 계층 계약.
 * 관리자 화면이 격리 종결 전 벤더 상태를 확인할 때 위임하는 조회 전용 포트.
 * 구현체는 application 계층의 {@code PgVendorStatusQueryServiceImpl}.
 */
public interface PgVendorStatusQueryService {

    /**
     * 주문번호 기준으로 벤더에 상태를 1회 조회해 승인/실패/확인불가 판정으로 접는다.
     * 조회 과정의 실패(예외 포함)를 이 메서드는 던지지 않는다 — 항상 판정값을 반환한다.
     *
     * @param orderId 주문 ID
     * @return 벤더 상태 조회 판정
     */
    PgVendorStatusView getVendorStatus(String orderId);
}
