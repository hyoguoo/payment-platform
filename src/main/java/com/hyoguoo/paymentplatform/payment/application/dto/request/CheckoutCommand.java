package com.hyoguoo.paymentplatform.payment.application.dto.request;

import com.hyoguoo.paymentplatform.payment.application.dto.vo.OrderedProduct;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CheckoutCommand {

    private final Long userId;
    private final List<OrderedProduct> orderedProductList;
}
