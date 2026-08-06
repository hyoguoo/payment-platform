package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgVendorStatusInfo;

/**
 * pg-service 벤더 상태 조회 아웃바운드 포트 — 격리 종결 판단 직전 벤더에 1회 묻는다.
 *
 * <p>{@link PgAttemptHistoryPort} 와 달리 이 포트는 예외를 던지지 않는다. 통신 실패든 pg 자체
 * 오류든 구현체가 확인 불가({@code PgVendorStatusJudgement.UNKNOWN}) 로 접어 반환한다 — 호출부가
 * 승인/실패/확인불가 세 갈래 분기 하나로 판정을 끝낼 수 있어야 하고, 확인 불가는 격리 종결을
 * 허용하는 분기라 예외 처리 분기가 늘어나면 그 경로가 새는 지점이 되기 때문이다.
 */
public interface PgVendorStatusPort {

    PgVendorStatusInfo lookup(String orderId);
}
