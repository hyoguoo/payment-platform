package com.hyoguoo.paymentplatform.order.presentation;

import com.hyoguoo.paymentplatform.order.application.dto.request.OrderConfirmInfo;
import com.hyoguoo.paymentplatform.order.application.dto.request.OrderCreateInfo;
import com.hyoguoo.paymentplatform.order.presentation.dto.request.OrderConfirmRequest;
import com.hyoguoo.paymentplatform.order.presentation.dto.request.OrderCreateRequest;
import com.hyoguoo.paymentplatform.order.application.dto.response.OrderConfirmResponse;
import com.hyoguoo.paymentplatform.order.application.dto.response.OrderCreateResponse;
import com.hyoguoo.paymentplatform.order.presentation.port.OrderService;
import jakarta.validation.Valid;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Builder
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/api/v1/orders/create")
    public OrderCreateResponse createOrder(
            @RequestBody @Valid OrderCreateRequest orderCreateRequest
    ) {
        OrderCreateInfo orderCreateInfo = OrderPresentationMapper.toOrderCreateInfo(
                orderCreateRequest, orderCreateRequest.getUserId()
        );
        return orderService.createOrder(orderCreateInfo);
    }

    @PostMapping("/api/v1/orders/confirm")
    public OrderConfirmResponse confirmOrder(
            @RequestBody @Valid OrderConfirmRequest orderConfirmRequest
    ) {
        OrderConfirmInfo orderConfirmInfo = OrderPresentationMapper.toOrderConfirmInfo(
                orderConfirmRequest,
                orderConfirmRequest.getUserId()
        );
        return orderService.confirmOrder(orderConfirmInfo);
    }
}
