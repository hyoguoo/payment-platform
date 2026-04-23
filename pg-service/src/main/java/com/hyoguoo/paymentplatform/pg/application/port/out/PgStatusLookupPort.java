package com.hyoguoo.paymentplatform.pg.application.port.out;

import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;

/**
 * pg-service outbound 포트 — 벤더 상태 조회 전담 계약.
 * T3.5-05: PgGatewayPort 분해 — getStatusByOrderId 전담 포트.
 *
 * <p>사용처:
 * <ul>
 *   <li>{@code DuplicateApprovalHandler} — 중복 승인 시 벤더 상태 선행 조회</li>
 *   <li>{@code PgFinalConfirmationGate} — FCG 최종 상태 확인</li>
 * </ul>
 *
 * <p>구현체(TossPaymentGatewayStrategy, NicepayPaymentGatewayStrategy)는 infrastructure 계층에 위치.
 */
public interface PgStatusLookupPort {

    boolean supports(PgVendorType vendorType);

    // 복구 사이클에서 orderId 기반 상태 선행 조회 경로에서 사용
    PgStatusResult getStatusByOrderId(String orderId)
            throws PgGatewayRetryableException, PgGatewayNonRetryableException;
}
