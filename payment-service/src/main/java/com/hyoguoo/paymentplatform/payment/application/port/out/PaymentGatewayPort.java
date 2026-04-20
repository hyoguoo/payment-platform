package com.hyoguoo.paymentplatform.payment.application.port.out;

import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayRetryableException;

/**
 * 결제 게이트웨이 outbound port — confirm/cancel 전담.
 * ADR-02: payment↔pg 간 상태 조회(getStatus)는 Kafka only.
 * getStatus 메서드는 이 port에 존재하지 않는다.
 * Phase 2 이후 pg-service Kafka 소비로 교체 예정.
 */
public interface PaymentGatewayPort {

    PaymentConfirmResult confirm(PaymentConfirmRequest request)
            throws PaymentGatewayRetryableException, PaymentGatewayNonRetryableException;

    PaymentCancelResult cancel(PaymentCancelRequest request);
}
