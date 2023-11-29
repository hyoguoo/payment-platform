package study.paymentintegrationserver.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;
import study.paymentintegrationserver.dto.order.*;
import study.paymentintegrationserver.service.OrderService;

import static org.springframework.data.domain.Sort.Direction.DESC;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping
    public Slice<OrderFindResponse> findOrderListWithCursor(@PageableDefault(size = 20, sort = "id", direction = DESC) Pageable pageable,
                                                            @RequestParam(value = "cursor", defaultValue = Long.MAX_VALUE + "") Long cursor) {
        return orderService.findOrderListWithCursor(pageable, cursor);
    }

    @PostMapping("/create")
    public OrderCreateResponse createOrder(@RequestBody @Valid OrderCreateRequest orderCreateRequest) {
        return orderService.createOrder(orderCreateRequest);
    }

    @PostMapping("/confirm")
    public OrderConfirmResponse confirmOrder(@RequestBody @Valid OrderConfirmRequest orderConfirmRequest) {
        return orderService.confirmOrder(orderConfirmRequest);
    }
}
