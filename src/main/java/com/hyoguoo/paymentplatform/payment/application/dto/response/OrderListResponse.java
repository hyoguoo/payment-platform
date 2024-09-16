package com.hyoguoo.paymentplatform.payment.application.dto.response;

import com.hyoguoo.paymentplatform.payment.domain.PaymentOrder;
import java.util.List;
import lombok.Getter;
import org.springframework.data.domain.Page;

@Getter
public class OrderListResponse {

    private final List<OrderFindResponse> content;
    private final int totalPages;
    private final long totalElements;
    private final int number;
    private final int size;

    public OrderListResponse(Page<PaymentOrder> orderInfoPage) {
        this.content = orderInfoPage.getContent().stream().map(OrderFindResponse::new).toList();
        this.totalPages = orderInfoPage.getTotalPages();
        this.totalElements = orderInfoPage.getTotalElements();
        this.number = orderInfoPage.getNumber();
        this.size = orderInfoPage.getSize();
    }
}
