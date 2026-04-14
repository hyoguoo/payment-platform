package com.hyoguoo.paymentplatform.payment.infrastructure.internal;

import com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayPort;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;
import com.hyoguoo.paymentplatform.payment.domain.enums.PaymentGatewayType;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayNonRetryableException;
import com.hyoguoo.paymentplatform.payment.exception.PaymentGatewayRetryableException;
import com.hyoguoo.paymentplatform.payment.infrastructure.gateway.PaymentGatewayFactory;
import com.hyoguoo.paymentplatform.payment.infrastructure.gateway.PaymentGatewayProperties;
import com.hyoguoo.paymentplatform.payment.infrastructure.gateway.PaymentGatewayStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InternalPaymentGatewayAdapter implements PaymentGatewayPort {

    private final PaymentGatewayFactory factory;
    private final PaymentGatewayProperties properties;

    @Override
    public PaymentConfirmResult confirm(PaymentConfirmRequest request)
            throws PaymentGatewayRetryableException, PaymentGatewayNonRetryableException {
        PaymentGatewayStrategy strategy = factory.getStrategy(properties.getType());
        return strategy.confirm(request);
    }

    @Override
    public PaymentCancelResult cancel(PaymentCancelRequest request) {
        PaymentGatewayStrategy strategy = factory.getStrategy(properties.getType());
        return strategy.cancel(request);
    }

    @Override
    public PaymentStatusResult getStatus(String paymentKey, PaymentGatewayType gatewayType) {
        PaymentGatewayType resolvedType = resolveGatewayType(gatewayType);
        PaymentGatewayStrategy strategy = factory.getStrategy(resolvedType);
        return strategy.getStatus(paymentKey, resolvedType);
    }

    @Override
    public PaymentStatusResult getStatusByOrderId(String orderId, PaymentGatewayType gatewayType)
            throws PaymentGatewayRetryableException, PaymentGatewayNonRetryableException {
        PaymentGatewayType resolvedType = resolveGatewayType(gatewayType);
        PaymentGatewayStrategy strategy = factory.getStrategy(resolvedType);
        return strategy.getStatusByOrderId(orderId, resolvedType);
    }

    /**
     * gatewayType이 null인 경우(T13 이전의 기존 레코드) properties 기본값으로 폴백한다.
     * T13 DB 마이그레이션 완료 후 null 분기는 제거 예정.
     */
    private PaymentGatewayType resolveGatewayType(PaymentGatewayType gatewayType) {
        return gatewayType != null ? gatewayType : properties.getType();
    }
}
