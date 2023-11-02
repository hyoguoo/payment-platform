package study.paymentintegrationserver.dto.payment;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

@Getter
@RequiredArgsConstructor
public class PaymentConfirmRequest {

    private final String orderId;
    private final BigDecimal amount;
    private final String paymentKey;
}
