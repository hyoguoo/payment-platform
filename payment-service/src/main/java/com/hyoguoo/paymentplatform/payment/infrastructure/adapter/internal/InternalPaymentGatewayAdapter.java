package com.hyoguoo.paymentplatform.payment.infrastructure.adapter.internal;

import com.hyoguoo.paymentplatform.payment.application.port.out.PaymentGatewayPort;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayRetryableException;
import com.hyoguoo.paymentplatform.payment.infrastructure.gateway.PaymentGatewayFactory;
import com.hyoguoo.paymentplatform.payment.infrastructure.gateway.PaymentGatewayProperties;
import com.hyoguoo.paymentplatform.payment.infrastructure.gateway.PaymentGatewayStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 결제 게이트웨이 내부 호출 어댑터 — confirm/cancel 전담 (Phase 1 모놀리스 경계).
 * ADR-02: getStatus는 이 어댑터에 존재하지 않는다.
 * Phase 2 이후 pg-service Kafka 연동으로 교체 예정.
 */
@Component("paymentGatewayLookupAdapter")
@RequiredArgsConstructor
public class InternalPaymentGatewayAdapter implements PaymentGatewayPort {

    private final PaymentGatewayFactory factory;
    private final PaymentGatewayProperties properties;

    @Override
    public PaymentConfirmResult confirm(PaymentConfirmRequest request)
            throws PaymentGatewayRetryableException, PaymentGatewayNonRetryableException {
        PaymentGatewayStrategy strategy = factory.getStrategy(resolveGatewayType(request.gatewayType()));
        return strategy.confirm(request);
    }

    @Override
    public PaymentCancelResult cancel(PaymentCancelRequest request) {
        PaymentGatewayStrategy strategy = factory.getStrategy(resolveGatewayType(request.gatewayType()));
        return strategy.cancel(request);
    }

    /**
     * gatewayType이 null인 경우(T13 이전의 기존 레코드) properties 기본값으로 폴백한다.
     * T13 DB 마이그레이션 완료 후 null 분기는 제거 예정.
     */
    private PaymentGatewayType resolveGatewayType(PaymentGatewayType gatewayType) {
        return gatewayType != null ? gatewayType : properties.getType();
    }
}
