package com.hyoguoo.paymentplatform.payment.application.dto.request;

import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CheckoutCommand {

    private final Long userId;
    private final BigDecimal amount;
    private final OrderedProduct orderedProduct;
}
