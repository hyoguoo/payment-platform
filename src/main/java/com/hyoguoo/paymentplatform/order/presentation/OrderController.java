package com.hyoguoo.paymentplatform.order.presentation;

import com.hyoguoo.paymentplatform.order.presentation.port.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import study.paymentintegrationserver.dto.order.OrderConfirmRequest;
import study.paymentintegrationserver.dto.order.OrderConfirmResponse;
import study.paymentintegrationserver.dto.order.OrderCreateRequest;
import study.paymentintegrationserver.dto.order.OrderCreateResponse;

@RestController
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/api/v1/orders/create")
    public OrderCreateResponse createOrder(
            @RequestBody @Valid OrderCreateRequest orderCreateRequest
    ) {
        return orderService.createOrder(orderCreateRequest);
    }

    @PostMapping("/api/v1/orders/confirm")
    public OrderConfirmResponse confirmOrder(
            @RequestBody @Valid OrderConfirmRequest orderConfirmRequest
    ) {
        return orderService.confirmOrder(orderConfirmRequest);
    }
}
