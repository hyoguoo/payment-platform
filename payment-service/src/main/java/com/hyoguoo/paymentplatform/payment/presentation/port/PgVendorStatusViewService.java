package com.hyoguoo.paymentplatform.payment.presentation.port;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgVendorStatusInfo;

public interface PgVendorStatusViewService {

    /**
     * 주문번호 기준 pg-service 벤더 상태를 1회 조회한다. 관리자가 격리 상세 화면에서 버튼을
     * 눌렀을 때만 호출된다 — 상세 진입 시 자동 조회하지 않는다(외부 호출이라 매번 느려지고
     * 보지도 않을 조회가 벤더에 나간다).
     *
     * <p>조회 실패는 이 계층까지 예외로 전파되지 않는다 — {@link PgVendorStatusInfo#judgement()}
     * 가 {@code UNKNOWN} 이면 벤더 응답이 미확정이었는지 조회 자체가 실패했는지를 화면에서
     * 구분하지 않는다. 둘 다 종결을 막지 않는 같은 처리라서다.
     *
     * @param orderId 조회 대상 주문 ID
     * @return 벤더 상태 조회 결과
     */
    PgVendorStatusInfo getVendorStatus(String orderId);
}
