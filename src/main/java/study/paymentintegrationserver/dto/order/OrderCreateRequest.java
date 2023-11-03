package study.paymentintegrationserver.dto.order;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

@Getter
@RequiredArgsConstructor
public class OrderCreateRequest {

    private final String orderId;
    private final BigDecimal amount;
}
