package com.hyoguoo.paymentplatform.payment.infrastructure.internal;

import com.hyoguoo.paymentplatform.payment.application.port.PaymentGatewayPort;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentCancelResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmRequest;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentConfirmResult;
import com.hyoguoo.paymentplatform.payment.domain.dto.PaymentStatusResult;
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
    public PaymentStatusResult getStatus(String paymentKey) {
        PaymentGatewayStrategy strategy = factory.getStrategy(properties.getType());
        return strategy.getStatus(paymentKey);
    }

    @Override
    public PaymentConfirmResult confirm(PaymentConfirmRequest request) {
        PaymentGatewayStrategy strategy = factory.getStrategy(properties.getType());
        return strategy.confirm(request);
    }

    @Override
    public PaymentCancelResult cancel(PaymentCancelRequest request) {
        PaymentGatewayStrategy strategy = factory.getStrategy(properties.getType());
        return strategy.cancel(request);
    }
}
