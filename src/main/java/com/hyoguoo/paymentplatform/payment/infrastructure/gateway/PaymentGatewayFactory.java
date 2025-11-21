package com.hyoguoo.paymentplatform.payment.infrastructure.gateway;

import com.hyoguoo.paymentplatform.payment.exception.UnsupportedPaymentGatewayException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentGatewayFactory {

    private final List<PaymentGatewayStrategy> strategies;

    public PaymentGatewayStrategy getStrategy(PaymentGatewayType type) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(type))
                .findFirst()
                .orElseThrow(() -> UnsupportedPaymentGatewayException.of(type));
    }
}
