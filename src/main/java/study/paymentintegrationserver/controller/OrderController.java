package study.paymentintegrationserver.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Slice;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import study.paymentintegrationserver.dto.order.OrderConfirmRequest;
import study.paymentintegrationserver.dto.order.OrderConfirmResponse;
import study.paymentintegrationserver.dto.order.OrderCreateRequest;
import study.paymentintegrationserver.dto.order.OrderCreateResponse;
import study.paymentintegrationserver.dto.order.OrderFindResponse;
import study.paymentintegrationserver.service.OrderService;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    public Slice<OrderFindResponse> findOrderListWithCursor(
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "cursor", defaultValue = Long.MAX_VALUE + "") Long cursor
    ) {
        return orderService.findOrderListWithCursor(size, cursor);
    }

    @PostMapping("/create")
    public OrderCreateResponse createOrder(
            @RequestBody @Valid OrderCreateRequest orderCreateRequest
    ) {
        return orderService.createOrder(orderCreateRequest);
    }

    @PostMapping("/confirm")
    public OrderConfirmResponse confirmOrder(
            @RequestBody @Valid OrderConfirmRequest orderConfirmRequest
    ) {
        return orderService.confirmOrder(orderConfirmRequest);
    }
}
