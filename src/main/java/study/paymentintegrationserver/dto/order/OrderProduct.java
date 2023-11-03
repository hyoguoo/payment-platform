package study.paymentintegrationserver.dto.order;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class OrderProduct {
    private final Long productId;
    private final Integer quantity;
}
