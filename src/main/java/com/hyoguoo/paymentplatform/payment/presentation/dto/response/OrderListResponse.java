package com.hyoguoo.paymentplatform.payment.presentation.dto.response;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class OrderListResponse {

    private final List<OrderFindResponse> content;
    private final int totalPages;
    private final long totalElements;
    private final int number;
    private final int size;
}
