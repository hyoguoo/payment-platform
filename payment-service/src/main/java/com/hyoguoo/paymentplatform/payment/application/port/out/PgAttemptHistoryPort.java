package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.application.dto.admin.PgAttemptHistoryInfo;

/**
 * pg-service 확정 시도 이력 조회 아웃바운드 포트 — 관리자 조회 전용.
 *
 * <p>결제 승인 경로가 쓰는 {@link ProductPort} / {@link UserPort} 와 별개다.
 * 승인 경로 포트에 관리자 조회 용도가 섞이면 나중에 떼어내기 어렵다.
 */
public interface PgAttemptHistoryPort {

    PgAttemptHistoryInfo getAttemptHistory(String orderId);
}
