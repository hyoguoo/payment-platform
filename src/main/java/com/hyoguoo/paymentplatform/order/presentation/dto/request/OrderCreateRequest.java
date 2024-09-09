package com.hyoguoo.paymentplatform.order.presentation.dto.request;

import com.hyoguoo.paymentplatform.order.application.dto.vo.OrderProduct;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class OrderCreateRequest {

    private final Long userId;
    private final BigDecimal amount;
    private final OrderProduct orderProduct;
}
