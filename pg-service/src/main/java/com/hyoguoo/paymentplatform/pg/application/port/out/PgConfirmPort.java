package com.hyoguoo.paymentplatform.pg.application.port.out;

import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmRequest;
import com.hyoguoo.paymentplatform.pg.application.dto.PgConfirmResult;
import com.hyoguoo.paymentplatform.pg.domain.enums.PgVendorType;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.pg.exception.PgGatewayRetryableException;

/**
 * pg-service outbound 포트 — 벤더 승인 호출 전담 계약.
 * T3.5-05: PgGatewayPort 분해 — confirm 전담 포트.
 *
 * <p>사용처:
 * <ul>
 *   <li>{@code PgVendorCallService} — 신규 결제 승인 호출</li>
 * </ul>
 *
 * <p>구현체(TossPaymentGatewayStrategy, NicepayPaymentGatewayStrategy)는 infrastructure 계층에 위치.
 */
public interface PgConfirmPort {

    boolean supports(PgVendorType vendorType);

    PgConfirmResult confirm(PgConfirmRequest request)
            throws PgGatewayRetryableException, PgGatewayNonRetryableException;
}
