package com.hyoguoo.paymentplatform.payment.infrastructure.gateway;

import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayRetryableException;

/**
 * 결제 게이트웨이 전략 인터페이스 — confirm/cancel 전담.
 * ADR-02: getStatus/getStatusByOrderId는 Kafka only(pg-service 담당). 이 인터페이스에 존재하지 않는다.
 */
public interface PaymentGatewayStrategy {

    boolean supports(PaymentGatewayType type);

    PaymentConfirmResult confirm(PaymentConfirmRequest request)
            throws PaymentGatewayRetryableException, PaymentGatewayNonRetryableException;

    PaymentCancelResult cancel(PaymentCancelRequest request);
}
