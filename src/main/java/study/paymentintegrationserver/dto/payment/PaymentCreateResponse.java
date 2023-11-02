package study.paymentintegrationserver.dto.payment;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class PaymentCreateResponse {

    private final String orderId;
}
