package com.hyoguoo.paymentplatform.pg.application.port.out;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.application.dto.PgStatusResult;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;

/**
 * pg-service outbound 포트 — 벤더 독립 PG 호출 계약.
 * ADR-21: pg-service가 벤더 선택·재시도·상태 조회를 전부 내부에서 수행.
 * 구현체(TossPaymentGatewayStrategy, NicepayPaymentGatewayStrategy)는 infrastructure 계층에 위치.
 */
public interface PgGatewayPort {

    boolean supports(PgVendorType vendorType);

    PgConfirmResult confirm(PgConfirmRequest request)
            throws PgGatewayRetryableException, PgGatewayNonRetryableException;

    // 복구 사이클에서 orderId 기반 상태 선행 조회 경로에서 사용
    PgStatusResult getStatusByOrderId(String orderId)
            throws PgGatewayRetryableException, PgGatewayNonRetryableException;
}
