package study.paymentintegrationserver.dto.payment;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

@Getter
@RequiredArgsConstructor
public class PaymentCreateRequest {

    private final String orderId;
    private final BigDecimal amount;
}
